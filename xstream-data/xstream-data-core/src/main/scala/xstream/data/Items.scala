package xstream.data

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import org.apache.arrow.vector.{VarCharVector,VectorSchemaRoot}
import org.apache.arrow.vector.types.pojo.{ArrowType,Field,FieldType,Schema}
import org.apache.arrow.vector.dictionary.DictionaryProvider
import scala.jdk.CollectionConverters._

/** Serializable resource identity, never a credential or an open handle. None views means unknown. */
final case class ItemReference(uri:String,name:String,kind:String="unknown",format:String="",
  views:Option[Vector[String]]=None,metadata:JsonNode=Data.mapper.createObjectNode()) {
  ItemReferences.validate(uri)
  require(Set("data","folder","unknown")(kind),"invalid Item reference kind")
  require(metadata!=null && metadata.isObject && Data.mapper.writeValueAsBytes(metadata).length<=65536,"invalid Item metadata")
  require(views.forall(_.forall(_.matches("[a-z][a-z0-9_-]*"))),"invalid Item views")
}
final case class ItemsSelection(recursive:Boolean=false,uriColumn:String="uri",mode:String="auto")
object ItemReferences {
  val View=new DataViewType[ItemsSelection]("items")
  val columns=Vector("uri","name","kind","format","views","metadata")
  val schema=ArrowStreamMetadata.schema(new Schema(columns.map(n=>new Field(n,
    new FieldType(n=="views",ArrowType.Utf8.INSTANCE,null),null)).asJava),"items",Data.mapper.createObjectNode().put("version",1).put("cardinality","many"))
  val singleSchema=ArrowStreamMetadata.schema(schema,"items",Data.mapper.createObjectNode().put("version",1).put("cardinality","one"))
  def validate(value:String):Unit={
    require(value!=null && value.nonEmpty && value.length<=16384 && !value.exists(_.isControl) && !value.contains("\\"),"invalid Item URI")
    val u=URI.create(value)
    require(!u.isOpaque && u.getUserInfo==null && u.getQuery==null && u.getFragment==null,"Item URI cannot contain credentials, query or fragment")
    require(Option(u.getPath).exists(_.nonEmpty) && !u.getPath.split("/",-1).exists(p=>p=="." || p==".."),"invalid Item URI path")
    if(u.getScheme!=null){
      if(u.getScheme=="context")require(u.getRawAuthority==null && u.getPath.startsWith("/"),"invalid context URI")
      else require(u.getHost!=null && (u.getPort == -1 || u.getPort>0 && u.getPort<=65535),"invalid remote Item URI")
    }else require(u.getRawAuthority==null,"invalid relative Item URI")
  }
  /** Relative references belong to the selected dataset; never resolve against a host path. */
  def resolve(value:String,origin:String):String={
    validate(value);validate(origin)
    val u=URI.create(value);val base=URI.create(origin)
    val result=if(u.getScheme=="context" && base.getScheme!=null && base.getScheme!="context") {
      new URI(base.getScheme,base.getAuthority,u.getPath,null,null).toASCIIString
    }else if(u.isAbsolute)value else if(value.startsWith("/")) {
      val ds=base.getPath.stripPrefix("/").takeWhile(_!='/')
      require(ds.nonEmpty,"Item origin requires a dataset")
      new URI(base.getScheme,base.getAuthority,"/"+ds+u.getPath,null,null).toASCIIString
    }else base.resolve(u).toASCIIString
    validate(result);result
  }
  def fromItem(item:Item,views:Seq[String]):ItemReference={
    val meta=Data.mapper.createObjectNode()
    item.totalSizeBytes.foreach(meta.put("size_bytes",_));item.recordCount.foreach(meta.put("record_count",_))
    item.creationTimeMs.foreach(meta.put("created_at_ms",_));item.lastModifiedTimeMs.foreach(meta.put("modified_at_ms",_))
    ItemReference(item.path,item.path.split('/').lastOption.getOrElse(""),item.kind,item.format,Some(views.toVector),meta)
  }
  def write(root:VectorSchemaRoot,index:Int,item:ItemReference):Unit={
    val values=Vector(item.uri,item.name,item.kind,item.format,item.views.map(v=>Data.mapper.writeValueAsString(v.asJava)).orNull,item.metadata.toString)
    columns.zip(values).foreach {case(n,v)=>val vector=root.getVector(n).asInstanceOf[VarCharVector];if(v==null)vector.setNull(index)else vector.setSafe(index,v.getBytes(UTF_8))}
  }
  def read(root:VectorSchemaRoot,index:Int):ItemReference={
    def value(n:String):String={val v=root.getVector(n).asInstanceOf[VarCharVector];require(!v.isNull(index),"missing Item field: "+n);new String(v.get(index),UTF_8)}
    val v=root.getVector("views").asInstanceOf[VarCharVector]
    val views=if(v.isNull(index))None else {val n=Data.mapper.readTree(v.get(index));require(n.isArray && n.elements.asScala.forall(_.isTextual),"invalid Item views");Some(n.elements.asScala.map(_.asText).toVector)}
    ItemReference(value("uri"),value("name"),value("kind"),value("format"),views,Data.mapper.readTree(value("metadata")))
  }
  def validateSchema(s:Schema):Unit=require(ArrowStreamMetadata.view(s)=="items" && ArrowStreamMetadata.logical(s)==ArrowStreamMetadata.logical(schema),"invalid items schema")
  def foreach(stream:ArrowDataStream,origin:Option[String]=None)(f:ItemReference=>Unit):Unit=try stream.consume(new ArrowConsumer {
    def start(s:Schema,d:DictionaryProvider):Unit=validateSchema(s)
    def accept(b:ArrowBatch):Unit=(0 until b.root.getRowCount).foreach { i=>val item=read(b.root,i);f(origin.fold(item)(u=>item.copy(uri=resolve(item.uri,u)))) }
  })finally stream.close()
  /** Rebase only relative rows at a transport boundary; absolute remote references retain identity. */
  def qualify(out:ArrowConsumer,origin:String):ArrowConsumer=new ArrowConsumer {
    private var items=false
    def start(s:Schema,d:DictionaryProvider):Unit={items=ArrowStreamMetadata.view(s)=="items";if(items)validateSchema(s);out.start(s,d)}
    def accept(b:ArrowBatch):Unit={
      if(items)(0 until b.root.getRowCount).foreach { i=>val item=read(b.root,i);write(b.root,i,item.copy(uri=resolve(item.uri,origin))) }
      out.accept(b)
    }
  }
}
final class ItemsViewSupport extends DataViewSupport {
  type Selection=ItemsSelection
  val id="items-v1";val kind=ItemReferences.View
  def selection(n:JsonNode):ItemsSelection={
    require(n!=null && n.isObject && n.fieldNames.asScala.toSet.subsetOf(Set("recursive","uri_column","mode")),"invalid items selection")
    require(!n.has("recursive") || n.path("recursive").isBoolean,"recursive must be boolean")
    require(!n.has("uri_column") || n.path("uri_column").isTextual && n.path("uri_column").asText.nonEmpty,"uri_column must be a nonempty string")
    require(!n.has("mode") || n.path("mode").isTextual && Set("auto","folder","csv")(n.path("mode").asText),"mode must be auto, folder or csv")
    ItemsSelection(n.path("recursive").asBoolean(false),n.path("uri_column").asText("uri"),n.path("mode").asText("auto"))
  }
  def selectionJson(s:ItemsSelection):JsonNode=Data.mapper.createObjectNode().put("recursive",s.recursive).put("uri_column",s.uriColumn).put("mode",s.mode)
  override def selectionSchema:JsonNode=Data.mapper.readTree("""{"type":"object","properties":{"mode":{"type":"string","enum":["auto","folder","csv"],"default":"auto"},"recursive":{"type":"boolean","default":false},"uri_column":{"type":"string","default":"uri"}},"additionalProperties":false}""")
  override def preview(b:ArrowBatch,maxRows:Int,maxValues:Int):Vector[JsonNode]=(0 until math.min(maxRows,b.root.getRowCount)).map { i=>
    val item=ItemReferences.read(b.root,i);val n=Data.mapper.createObjectNode().put("uri",item.uri).put("name",item.name).put("kind",item.kind).put("format",item.format)
    item.views.foreach(v=>{val a=n.putArray("views");v.foreach(a.add)});n.set[JsonNode]("metadata",item.metadata);n:JsonNode
  }.toVector
}
