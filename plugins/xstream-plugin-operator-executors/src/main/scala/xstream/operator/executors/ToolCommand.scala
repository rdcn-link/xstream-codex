package xstream.operator.executors

import com.fasterxml.jackson.databind.JsonNode
import xstream.execution.{OperatorProgram,OperatorCommand}
import xstream.data.Data
import scala.jdk.CollectionConverters._

/** Configurable byte-stream/file adaptation. Configuration validation belongs to the executor plugin. */
private[executors] object ToolCommand {
  def validate(c:JsonNode):Unit={
    require(c.isObject && c.fieldNames.asScala.toSet==Set("mode","args","inputs","outputs","artifact_mode") && c.path("mode").asText=="tool","invalid tool configuration")
    require(Set("none","script","executable")(c.path("artifact_mode").asText),"invalid tool artifact mode")
    Seq("inputs","outputs").foreach { direction=>
      val ports=c.path(direction);require(ports.isObject && ports.size<=32,"tool ports must be mappings with at most 32 ports")
      ports.fields.asScala.foreach { e=>
        require(e.getKey.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"),"invalid tool port")
        val d=e.getValue;val output=direction=="outputs"
        require(d.isObject && d.fieldNames.asScala.toSet==(if(output)Set("view","binding","format")else Set("view","binding")),"invalid tool port settings")
        require(d.path("view").asText=="bytes","tool adapters support bytes ports; use an SDK operator for structured streams")
        require(Set("file",if(output)"stdout"else"stdin")(d.path("binding").asText),"invalid tool port binding")
        if(output)require(d.path("format").isTextual && d.path("format").asText.nonEmpty,"tool output requires format")
      }
      require(ports.elements.asScala.count(_.path("binding").asText!="file")<=1,"tool supports at most one stdin and one stdout")
    }
    require(c.path("args").isArray && c.path("args").size<=256,"tool args must be a list of at most 256 entries")
    c.path("args").elements.asScala.foreach { a=>
      if(a.isTextual)require(!a.asText.contains('\u0000'),"tool argument contains NUL")
      else {
        require(a.isObject && a.size==1 && Set("param","input","output")(a.fieldNames.next()),"argument requires a string or a single param/input/output reference")
        val kind=a.fieldNames.next();val name=a.path(kind)
        require(name.isTextual && name.asText.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"),"invalid argument reference")
        if(kind!="param")require(c.path(if(kind=="input")"inputs"else"outputs").path(name.asText).path("binding").asText=="file","file argument must reference a declared file port")
      }
    }
    Seq("input"->"inputs","output"->"outputs").foreach {case(kind,ports)=>
      c.path(ports).fields.asScala.filter(_.getValue.path("binding").asText=="file").foreach(e=>require(c.path("args").elements.asScala.exists(_.path(kind).asText==e.getKey),"file port must be referenced in args"))
    }
  }
  def command(p:OperatorProgram,path:Option[String]):OperatorCommand={
    val c=Data.mapper.valueToTree[JsonNode](p.configuration.asJava);validate(c)
    require(p.entrypoint.startsWith("/") && !p.entrypoint.contains('\u0000'),"tool requires absolute executable/interpreter path")
    require(path.isDefined==p.artifact.isDefined && path.isDefined==(c.path("artifact_mode").asText!="none"),"tool artifact mismatch")
    require(p.inputs==c.path("inputs").fieldNames.asScala.map(_->Set("bytes")).toMap,"tool input contracts differ")
    val config=c.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]().put("executable",p.entrypoint)
    path.foreach(config.put("artifact",_))
    val in=getClass.getResourceAsStream("/tool-bridge.py");require(in!=null,"tool bridge unavailable")
    val code=try in.readAllBytes()finally in.close()
    OperatorCommand(Some(Vector("/usr/local/bin/python","-u","-c",new String(code,java.nio.charset.StandardCharsets.UTF_8),Data.mapper.writeValueAsString(config))),arguments=Vector.empty,
      environment=Map("PYTHONDONTWRITEBYTECODE"->"1"),executableScratch=c.path("artifact_mode").asText=="executable")
  }
}
