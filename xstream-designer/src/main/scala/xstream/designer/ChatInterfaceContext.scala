package xstream.designer

import com.fasterxml.jackson.databind.JsonNode
import xstream.util.Json
import scala.jdk.CollectionConverters._

/** Public interface descriptions and example metadata; never includes connection credentials. */
private[designer] object ChatInterfaceContext {
 val guide="""This conversation is inside XStream Designer. Use feature_guide as the authoritative inventory of the entire interface, including New and Choose design target, canvas gestures, shortcuts, dialogs, dynamic parameter controls, inference, editors, logs and Chat. Explain actual English labels in the user's language. Use current_ui for visibility, disabled controls, selection, panels and connection status. Use the numbered examples and configured targets; do not invent controls or availability.
Chat provides instructions and complete Job proposals, not UI automation. Never claim to click controls, create a canvas, switch Target, load an example, edit the interface or execute/submit a Job. For UI-only questions return null job_yaml and variables_yaml, and give precise steps rather than silently replacing the draft. For a requested workflow, propose YAML for user review. Do not infer private credentials, file contents or samples from interface state. Context metadata may be cached and is not exhaustive."""
 val features:JsonNode={
  val in=getClass.getResourceAsStream("/designer-interface.json");require(in!=null,"Designer interface guide is missing")
  try Json.mapper.readTree(in)finally in.close()
 }
 private val controls=features.elements.asScala.flatMap(_.path("control_ids").elements.asScala.map(_.asText)).toSet

 def build(targets:JsonNode,examples:JsonNode,operators:JsonNode,request:JsonNode):JsonNode={
  val out=Json.obj().put("capabilities",guide).put("current_target",request.path("target").asText("local"))
  out.set[JsonNode]("feature_guide",features.deepCopy[JsonNode]())
  val choices=out.putArray("targets")
  targets.elements.asScala.foreach(t=>choices.addObject().put("value",t.path("value").asText).put("label",t.path("label").asText))
  val available=operators.elements.asScala.map(o=>o.path("name").asText+"@"+o.path("version").asText).toSet
  val cards=out.putArray("examples")
  examples.elements.asScala.zipWithIndex.foreach{case(example,index)=>
   val n=cards.addObject().put("number",f"${index+1}%02d")
   Seq("id","title","description","tags","step_count","requires").foreach(key=>Option(example.get(key)).foreach(v=>n.set[JsonNode](key,v)))
   val missing=example.path("requires").elements.asScala.map(_.asText).filterNot(available).toVector
   n.put("available_to_load",missing.isEmpty);val requirements=n.putArray("missing_operators");missing.foreach(requirements.add)
   val preferred=Documents.job(example.path("job_yaml").asText).path("designer").path("target")
   if(preferred.isTextual)n.put("preferred_target",preferred.asText)
  }
  val browser=request.path("ui_state");val state=out.putObject("current_ui")
  Seq("library_tab"->Set("operators","datasets","examples"),"details_tab"->Set("yaml","variables","validation","logs"),"inspector_tab"->Set("properties","parameters","outputs")).foreach{case(key,values)=>val v=browser.path(key).asText;if(values(v))state.put(key,v)}
  Seq("details_open","inspector_open","library_open","unapplied_yaml","edge_selected","has_pending_proposal").foreach(key=>if(browser.path(key).isBoolean)state.put(key,browser.path(key).asBoolean))
  val ids=Documents.job(request.path("job_yaml").asText,true).path("steps").elements.asScala.map(_.path("id").asText).toSet
  val selected=state.putArray("selected_steps");browser.path("selected_steps").elements.asScala.map(_.asText).filter(ids).take(100).foreach(selected.add)
  val statuses=state.putArray("controls")
  browser.path("controls").elements.asScala.take(100).foreach{control=>
   val id=control.path("id").asText
   if(controls(id)){val n=statuses.addObject().put("id",id);Seq("visible","enabled","checked").foreach(key=>if(control.path(key).isBoolean)n.put(key,control.path(key).asBoolean))}
  }
  val dialogs=state.putArray("dialogs");browser.path("dialogs").elements.asScala.map(_.asText).filter(Set("target-dialog","export-dialog","confirmation-dialog")).take(3).foreach(dialogs.add)
  Seq("zoom_percent"->(35,160),"logs_count"->(0,1000)).foreach{case(key,(min,max))=>val v=browser.path(key);if(v.isInt&&v.asInt>=min&&v.asInt<=max)state.put(key,v.asInt)}
  if(Set("all","info","warning","error")(browser.path("logs_filter").asText))state.put("logs_filter",browser.path("logs_filter").asText)
  Seq("catalog_status","repository_status","inference_status").foreach(key=>if(browser.path(key).isTextual)state.put(key,browser.path(key).asText.take(160)))
  out
 }
}
