package xstream.connector.dacp

import xstream.data._
import xstream.execution._
import xstream.connector.dacp.client.{Source,TlsConfig}
import java.nio.file.Path
import scala.jdk.CollectionConverters._

/** Installed through ServiceLoader; core does not recognize DACP URI syntax or credentials. */
final class DacpRemoteItemProvider extends RemoteItemProvider {
  val id="dacp"
  private var plugins:PluginEnvironment=null
  override def initialize(environment:PluginEnvironment):Unit={plugins=environment}
  def supports(uri:String):Boolean=java.net.URI.create(uri).getScheme=="dacp"
  private def predicate(p:Predicate):xstream.connector.dacp.client.Predicate={
    import xstream.connector.dacp.client.Expressions.col
    def literal(v:Literal):Any=v.kind match {
      case "integer"=>BigInt(v.value);case "decimal"=>BigDecimal(v.value);case "float"=>v.value.toDouble
      case "boolean"=>v.value.toBoolean;case "string"=>v.value
    }
    val c=col(p.column)
    p.op match {
      case "and"=>p.children.map(predicate).reduce(_ && _)
      case "or"=>p.children.map(predicate).reduce(_ || _)
      case "not"=> !predicate(p.children.head)
      case "is_null"=>c.isNull;case "is_not_null"=>c.isNotNull;case "in"=>c.isin(p.values.map(literal))
      case "eq"=>c === literal(p.values.head);case "ne"=>c =!= literal(p.values.head)
      case "lt"=>c < literal(p.values.head);case "le"=>c <= literal(p.values.head)
      case "gt"=>c > literal(p.values.head);case "ge"=>c >= literal(p.values.head)
    }
  }
  def open(config:com.fasterxml.jackson.databind.JsonNode,binding:RemoteItemRequest,allocator:org.apache.arrow.memory.BufferAllocator,cancel:StreamCancel):FrameSource={
    val uri=java.net.URI.create(binding.uri)
    require(uri.getHost!=null && uri.getUserInfo==null && uri.getQuery==null && uri.getFragment==null,"invalid remote Item URI")
    val origin=new java.net.URI(uri.getScheme,null,uri.getHost,uri.getPort,null,null,null).toASCIIString
    val node=config.path("nodes").elements.asScala.find(n=>n.path("url").asText.stripSuffix("/")==origin).getOrElse(throw new IllegalArgumentException("Remote Item node is not configured"))
    xstream.util.Json.fields(node,Set("url"),Set("ca","token_file"))
    val tls=TlsConfig(ca=Option(node.get("ca")).map(n=>Path.of(n.asText)),tokenFile=Option(node.get("token_file")).map(n=>Path.of(n.asText)))
    val parts=uri.getPath.stripPrefix("/").split("/",2);require(parts.length==2,"remote URI must address an Item")
    val dataset=cancel.add(Source.open(origin+"/"+parts(0),tls,math.min(30L,cancel.remainingSeconds)))
    try {
      val path="/"+parts(1);val metadata=dataset.describe(path);val view=ItemViews.select(binding.view,metadata.views)
      val item=cancel.add(dataset.open(path,predicate=binding.scan.flatMap(_.predicate).map(predicate),columns=binding.scan.flatMap(_.columns),limit=binding.scan.flatMap(_.limit)))
      try {
        if(!Set("records","bytes")(view)){
          require(binding.scan.isEmpty,"scientific views cannot use SQL")
          val codec=DataViewSupports.named(plugins,view)
          return ItemFrameStreams.remote(metadata.format,cancel){out=>
            try {val stream=item.asView(codec.kind,codec.selection(binding.selection.getOrElse(Data.mapper.createObjectNode())))
              try stream.consume(out)finally stream.close()
            }finally {item.close();dataset.close()}
          }
        }
        require(view!="bytes" || binding.scan.isEmpty,"bytes view cannot use SQL")
        val bytes=if(view=="bytes")Some(cancel.add(item.asBytes()))else None
        val records=if(view=="records")Some(cancel.add(item.asRecords()))else None
        new FrameSource {
          private var began=false;private var ended=false
          def next():Option[OperatorFrame]={
            cancel.active();if(!began){began=true;return Some(StreamProtocol.start("",view,metadata.format,records.map(_.root.getSchema),dictionaries=records.map(_.dictionaries).orNull))}
            if(ended)return None
            val data=bytes.map { in=>val buffer=new Array[Byte](65536);val n=in.read(buffer);if(n<0)Array.emptyByteArray else java.util.Arrays.copyOf(buffer,n) }.orElse(records.filter(_.next()).map(r=>StreamProtocol.batch(r.root,r.dictionaries)))
            data.filter(_.nonEmpty) match {case Some(b)=>Some(StreamProtocol.frame("data",data=b));case None=>ended=true;Some(StreamProtocol.frame("end"))}
          }
          def close():Unit=try item.close()finally dataset.close()
        }
      }catch{case scala.util.control.NonFatal(t)=>item.close();throw t}
    }catch{case scala.util.control.NonFatal(t)=>dataset.close();throw t}
  }
}
