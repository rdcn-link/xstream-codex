package xstream.operator

import com.fasterxml.jackson.databind.JsonNode

/** Graph responsibility, independent of the language or executor. */
sealed trait OperatorRole { def id:String }
object OperatorRole {
  case object Source extends OperatorRole { val id="source" }
  case object Transform extends OperatorRole { val id="transform" }
  case object Sink extends OperatorRole { val id="sink" }

  def read(definition:JsonNode):OperatorRole={
    require(definition!=null && definition.isObject && definition.path("role").isTextual,"operator role is required")
    definition.path("role").asText match {
      case "source"=>Source
      case "transform"=>Transform
      case "sink"=>Sink
      case _=>throw new IllegalArgumentException("operator role must be source, transform or sink")
    }
  }
  def validateInputs(role:OperatorRole,inputs:JsonNode):Unit={
    require(inputs!=null && inputs.isObject,"operator inputs must be a mapping")
    if(role==Source)require(inputs.size()==0,"Source operators cannot have input ports")
    else require(inputs.size()>0,s"${role.id} operators require an input port")
  }
  /** Applies when output ports are declared; published SDK operators infer their outputs. */
  def validateOutputs(role:OperatorRole,outputs:JsonNode):Unit={
    require(outputs!=null && outputs.isObject,"operator outputs must be a mapping")
    if(role==Sink)require(outputs.size()==0,"Sink operators cannot have output ports")
    else require(outputs.size()>0,s"${role.id} operators require an output port")
  }
}

object OperatorImplementation {
  val Supported=Set("sdk","command","script")
  def read(definition:JsonNode):String={
    require(definition!=null && definition.isObject && definition.path("type").isTextual && Supported(definition.path("type").asText),"operator type must be sdk, command or script")
    definition.path("type").asText
  }
}
