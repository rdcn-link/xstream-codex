package xstream.operator

import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

/** Output ports and their View selectors are public operator metadata, not inferred structure. */
object OutputViews {
  private def name(s:String)=s.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}")
  private def view(s:String)=s.matches("[a-z][a-z0-9_-]*") && s!="auto"
  def validate(definition:JsonNode):Unit={
    val outputs=definition.path("outputs")
    OperatorRole.validateOutputs(OperatorRole.read(definition),outputs)
    outputs.fields.asScala.foreach { e=>
      require(name(e.getKey),"invalid output port name")
      val d=e.getValue
      require(d.isObject,"invalid output port declaration")
      val v=d.path("view")
      require(v.isTextual && view(v.asText),"output View must name a View or variable")
      Option(d.get("view_from")).foreach { selector=>
        require(v.asText=="variable" && selector.isObject && selector.size==1 && Set("parameter","input")(selector.fieldNames.next()),"view_from requires a variable View and one parameter/input selector")
        val key=selector.fieldNames.next();val target=selector.path(key)
        require(target.isTextual && name(target.asText),"invalid output View selector")
        if(key=="input")require(definition.path("inputs").has(target.asText),"output View references unknown input")
        else {
          val p=definition.path("parameters").path("properties").path(target.asText)
          require(p.isObject && p.path("type").asText=="string","output View parameter must declare a string parameter")
          Option(p.get("enum")).foreach(a=>require(a.isArray && a.elements.asScala.forall(x=>x.isTextual && view(x.asText) && x.asText!="variable"),"output View parameter enum must contain concrete View names"))
        }
      }
      Option(d.get("description")).foreach(x=>require(x.isTextual,"output description must be text"))
      Option(d.get("formats")).foreach(x=>require(x.isArray && x.elements.asScala.forall(_.isTextual),"output formats must be a list of strings"))
      Option(d.get("schema")).foreach(x=>require(x.isObject,"output schema must be an object"))
      Option(d.get("cardinality")).foreach(x=>require(x.isTextual && Set("one","many")(x.asText) && v.isTextual && v.asText=="items","output cardinality requires an items View"))
    }
  }
  /** None means a parameter/input/Item metadata prerequisite is still unavailable. */
  def resolve(definition:JsonNode,params:JsonNode,inputs:Map[String,Set[String]]):Map[String,Option[String]]={
    validate(definition)
    definition.path("outputs").fields.asScala.map { e=>
      val d=e.getValue;val v=d.path("view");val from=d.path("view_from")
      val selected=if(v.asText!="variable")Some(v.asText)else if(from.has("input")){
        inputs.get(from.path("input").asText).filter(_.size==1).map(_.head).filter(_!="variable")
      }else if(from.has("parameter")){
        val key=from.path("parameter").asText
        Option(params.get(key)).orElse(Option(definition.path("parameters").path("properties").path(key).get("default"))).filter(x=>x.isTextual && view(x.asText) && x.asText!="variable" && !x.asText.startsWith("$")).map(_.asText)
      }else None
      selected.foreach(x=>require(view(x),"invalid resolved output View"))
      e.getKey->selected
    }.toMap
  }
}
