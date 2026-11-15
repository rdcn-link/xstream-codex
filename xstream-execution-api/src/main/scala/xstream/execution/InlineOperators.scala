package xstream.execution

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path
import scala.jdk.CollectionConverters._
import xstream.data.Plugin
import xstream.util.{Json,YamlDocument}

case class InlineOperator(name:String,definition:JsonNode)
case class PreparedInlineProgram(program:OperatorProgram,inputs:Map[String,PortContract],executor:Option[String]=None)
/** Local authoring adapters own their definition schema and never execute user code in the host. */
trait InlineOperatorProvider extends Plugin {
  def validate(definition:JsonNode):Unit
  def prepare(operator:InlineOperator,base:Path,stage:Path,maxBytes:Long,check:()=>Unit):PreparedInlineProgram
}
object InlineOperators {
  def definitions(job:JsonNode):Map[String,InlineOperator]={
    if(!job.has("operators"))return Map.empty
    val definitions=job.get("operators")
    require(definitions.isObject && definitions.size<=64,"operators must be a mapping of at most 64 inline definitions")
    definitions.fields.asScala.map { e=>
      require(e.getKey.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"),"invalid inline operator name")
      val d=e.getValue
      require(d.isObject && d.path("type").isTextual && d.path("type").asText.matches("[a-z][a-z0-9_-]*"),"inline operator requires a type")
      require(d.path("runtime").isTextual && d.path("runtime").asText.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}"),"inline operator requires a named runtime")
      val role=xstream.operator.OperatorRole.read(d)
      xstream.operator.OperatorRole.validateInputs(role,d.path("inputs"))
      xstream.operator.OutputViews.validate(d)
      e.getKey->InlineOperator(e.getKey,d.deepCopy[JsonNode]())
    }.toMap
  }
}
/** Applies before a remote connection is opened, and independently at every server entry point. */
object RemoteJobPolicy {
  val Message="Jobs containing inline definitions or local/source operators cannot be submitted to a remote DACP or Union server; publish the operators first"
  def validateYaml(yaml:String):Unit=validate(YamlDocument.parse(yaml))
  def validate(job:JsonNode):Unit={
    require(job.isObject,"job must be a mapping")
    require(!job.has("operators"),Message)
    job.path("steps").elements.asScala.foreach { step=>
      require(step.path("operator").isTextual && step.path("operator").asText.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*@[A-Za-z0-9_-][A-Za-z0-9_.-]*"),Message)
    }
  }
}
