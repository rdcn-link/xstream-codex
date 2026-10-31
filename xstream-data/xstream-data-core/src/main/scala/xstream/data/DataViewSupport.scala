package xstream.data

import com.fasterxml.jackson.databind.JsonNode

/** Optional request selection and bounded presentation support. All outputs are Arrow;
  * a representation plugin never implements wire serialization or transport framing.
  */
trait DataViewSupport extends Plugin {
  type Selection
  def kind:DataViewType[Selection]
  def selection(json:JsonNode):Selection
  def selectionJson(value:Selection):JsonNode
  final def descriptor(schema:org.apache.arrow.vector.types.pojo.Schema):JsonNode={
    val n=ArrowStreamMetadata.descriptor(schema).deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
    n.put("view",ArrowStreamMetadata.view(schema)).put("version",1)
  }
  def selectionSchema:JsonNode=Data.mapper.readTree("""{"type":"object","properties":{},"additionalProperties":false}""")
  def validateSelection(value:Selection,schema:org.apache.arrow.vector.types.pojo.Schema):Unit=()
  def preview(batch:ArrowBatch,maxRows:Int,maxValues:Int):Vector[JsonNode]=Vector.empty
  final def send(access:DatasetAccess,path:String,request:JsonNode,context:StreamContext,out:ArrowConsumer):Unit={
    val stream=access.requireView(path,kind).open(selection(request),context)
    try stream.consume(out)finally stream.close()
  }
  final def remoteView[S](requested:DataViewType[S])(read:(JsonNode,ArrowConsumer,StreamContext)=>Unit):Option[DataView[S]] =
    DataViews.when(requested,kind)(DataViews[Selection]((s,c,out)=>read(selectionJson(s),out,c)))
}
object DataViewSupports {
  val MaxMetadataBytes=1024*1024
  val MaxBlockBytes=ArrowRepresentations.MaxBlockBytes
  def all(environment:PluginEnvironment):Vector[DataViewSupport]={
    val supports=environment.services(classOf[DataViewSupport])
    require(supports.map(_.kind.name).distinct.size==supports.size,"duplicate data view support")
    supports
  }
  def named(environment:PluginEnvironment,name:String):DataViewSupport=all(environment).find(_.kind.name==name)
    .getOrElse(throw new IllegalArgumentException("unsupported stream view: "+name))
  def forType[S](environment:PluginEnvironment,kind:DataViewType[S]):Option[DataViewSupport]=all(environment).find(_.kind eq kind)
}
