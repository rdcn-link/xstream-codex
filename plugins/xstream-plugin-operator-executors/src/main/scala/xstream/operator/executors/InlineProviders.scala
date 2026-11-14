package xstream.operator.executors

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path,LinkOption,StandardCopyOption}
import scala.jdk.CollectionConverters._
import xstream.execution._
import xstream.data.Data

private[executors] object InlineFiles {
  def fields(d:JsonNode,allowed:Set[String]):Unit=require(d.isObject && d.fieldNames.asScala.toSet.subsetOf(allowed),"unknown inline operator setting")
  def copy(base:Path,value:JsonNode,stage:Path,max:Long,check:()=>Unit):Path={
    require(value.isTextual && value.asText.nonEmpty,"program path must be a nonempty string")
    val source=base.resolve(value.asText).normalize
    require(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS),"inline program must be a regular file")
    val name=source.getFileName.toString;require(name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*"),"invalid inline program filename")
    require(Files.size(source)>0 && Files.size(source)<=max,"inline program exceeds artifact size limit")
    val target=stage.resolve(name);val in=Files.newInputStream(source);val out=Files.newOutputStream(target)
    try {val buffer=new Array[Byte](65536);var size=0L;var n=in.read(buffer);while(n != -1){check();size+=n;require(size<=max,"inline program exceeds artifact size limit");out.write(buffer,0,n);n=in.read(buffer)}}finally {try in.close()finally out.close()}
    target
  }
}
final class InlineSdkProvider extends InlineOperatorProvider {
  val id="sdk"
  def validate(d:JsonNode):Unit={
    InlineFiles.fields(d,Set("type","role","runtime","program","inputs","parameters","description"))
    val p=d.path("program");InlineFiles.fields(p,Set("path","entrypoint"))
    require(p.path("path").isTextual,"SDK program requires path")
    require(!p.has("entrypoint") || p.path("entrypoint").isTextual,"entrypoint must be a string")
    OperatorPorts.read(d.path("inputs"))
    xstream.operator.OperatorRole.validateInputs(xstream.operator.OperatorRole.read(d),d.path("inputs"))
  }
  def prepare(op:InlineOperator,base:Path,stage:Path,maxBytes:Long,check:()=>Unit):PreparedInlineProgram={
    validate(op.definition)
    val file=InlineFiles.copy(base,op.definition.path("program").path("path"),stage,maxBytes,check)
    val inputs=OperatorPorts.read(op.definition.path("inputs"))
    PreparedInlineProgram(OperatorProgram("inline:"+op.name,op.definition.path("program").path("entrypoint").asText("operator"),Some(file),inputs=inputs.view.mapValues(_.representations).toMap),inputs)
  }
}
private[executors] object InlineTools {
  def validate(d:JsonNode,script:Boolean):Unit={
    InlineFiles.fields(d,Set("type","role","runtime","args","inputs","outputs","parameters","description") ++ (if(script)Set("interpreter","script")else Set("command","artifact")))
    val executable=d.path(if(script)"interpreter"else"command")
    require(executable.isTextual && executable.asText.startsWith("/") && !executable.asText.contains('\u0000'),"tool/interpreter must be an absolute container path")
    if(script){val s=d.path("script");InlineFiles.fields(s,Set("path","source"));require(s.has("path") != s.has("source"),"script requires exactly one of path or source");require(s.elements.asScala.forall(v=>v.isTextual && v.asText.nonEmpty),"script must contain text")}
    val c=Data.mapper.createObjectNode().put("mode","tool").put("artifact_mode",if(script)"script"else if(d.has("artifact"))"executable"else"none")
    Seq("inputs","outputs").foreach(k=>c.set[JsonNode](k,d.path(k)))
    c.set[JsonNode]("args",if(d.has("args"))d.path("args")else Data.mapper.createArrayNode())
    ToolCommand.validate(c)
    val role=xstream.operator.OperatorRole.read(d)
    xstream.operator.OperatorRole.validateInputs(role,d.path("inputs"))
    xstream.operator.OperatorRole.validateOutputs(role,d.path("outputs"))
  }
  def prepare(op:InlineOperator,base:Path,stage:Path,maxBytes:Long,check:()=>Unit,script:Boolean):PreparedInlineProgram={
    val d=op.definition;validate(d,script)
    val artifact=if(script){
      val s=d.path("script")
      Some(if(s.has("path"))InlineFiles.copy(base,s.path("path"),stage,maxBytes,check)else {
        val data=s.path("source").asText.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        require(data.length<=math.min(maxBytes,512L*1024),"inline script exceeds 512 KiB")
        val path=stage.resolve("script.source");Files.write(path,data);path
      })
    }else Option(d.get("artifact")).map(v=>InlineFiles.copy(base,v,stage,maxBytes,check))
    val c=Data.mapper.createObjectNode().put("mode","tool").put("artifact_mode",if(script)"script"else if(artifact.nonEmpty)"executable"else"none")
    c.set[JsonNode]("args",if(d.has("args"))d.path("args")else Data.mapper.createArrayNode())
    Seq("inputs","outputs").foreach(k=>c.set[JsonNode](k,d.path(k)))
    val inputs=d.path("inputs").fieldNames.asScala.map(_->PortContract(Set("bytes"),Set.empty,None)).toMap
    val config=Data.mapper.convertValue(c,classOf[java.util.LinkedHashMap[String,Object]]).asScala.toMap
    val entry=d.path(if(script)"interpreter"else"command").asText
    PreparedInlineProgram(OperatorProgram("inline:"+op.name,entry,artifact,config,inputs.view.mapValues(_.representations).toMap),inputs,Some("command"))
  }
}
final class InlineCommandProvider extends InlineOperatorProvider {
  val id="command"
  def validate(d:JsonNode):Unit=InlineTools.validate(d,false)
  def prepare(op:InlineOperator,base:Path,stage:Path,maxBytes:Long,check:()=>Unit):PreparedInlineProgram=InlineTools.prepare(op,base,stage,maxBytes,check,false)
}
final class InlineScriptProvider extends InlineOperatorProvider {
  val id="script"
  def validate(d:JsonNode):Unit=InlineTools.validate(d,true)
  def prepare(op:InlineOperator,base:Path,stage:Path,maxBytes:Long,check:()=>Unit):PreparedInlineProgram=InlineTools.prepare(op,base,stage,maxBytes,check,true)
}
