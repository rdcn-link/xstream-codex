package xstream.operator.inference

import com.fasterxml.jackson.databind.JsonNode
import com.github.dockerjava.api.DockerClient
import java.nio.file.{Path,Files}
import xstream.execution.{InlineOperator,InlineOperatorProvider,OperatorExecutor}
import scala.jdk.CollectionConverters._

case class InlineRuntime(image:String,executor:String)
object InlineRuntime {
  def read(n:JsonNode):Map[String,InlineRuntime]={
    if(n.isMissingNode)return Map.empty
    require(n.isObject,"inline_runtimes must be a mapping")
    n.fields.asScala.map { e=>
      val d=e.getValue
      require(e.getKey.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}") && d.isObject && d.fieldNames.asScala.toSet==Set("image","executor"),"invalid inline runtime")
      require(d.path("image").isTextual && d.path("image").asText.nonEmpty && d.path("executor").isTextual,"inline runtime needs image and executor")
      e.getKey->InlineRuntime(d.path("image").asText,d.path("executor").asText)
    }.toMap
  }
}
object InlineOperatorLibrary {
  def validate(config:OperatorEnvironment,operator:InlineOperator):Unit=
    config.plugins.byId(classOf[InlineOperatorProvider],operator.definition.path("type").asText).validate(operator.definition)
  def resolve(config:OperatorEnvironment,runtimes:Map[String,InlineRuntime],base:Path,operator:InlineOperator,docker:DockerClient,stage:Path,check:()=>Unit):ResolvedOperator={
    check();validate(config,operator)
    val runtime=runtimes.getOrElse(operator.definition.path("runtime").asText,throw new IllegalArgumentException("inline runtime is not configured: "+operator.definition.path("runtime").asText))
    val provider=config.plugins.byId(classOf[InlineOperatorProvider],operator.definition.path("type").asText)
    val prepared=provider.prepare(operator,base,Files.createDirectories(stage),config.operatorMaxBytes,check)
    val executor=prepared.executor.getOrElse(runtime.executor)
    val image=docker.inspectImageCmd(runtime.image).exec().getId
    val path=prepared.program.artifact.map(p=>"/operator/"+p.getFileName.toString)
    val command=config.plugins.byId(classOf[OperatorExecutor],executor).command(prepared.program,path)
    check()
    ResolvedOperator(image,prepared.program.artifact,path,command,prepared.inputs,Map.empty,executor,Some(prepared.program),role=Some(xstream.operator.OperatorRole.read(operator.definition)))
  }
}
