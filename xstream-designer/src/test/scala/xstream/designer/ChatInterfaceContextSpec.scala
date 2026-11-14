package xstream.designer
import org.scalatest.funsuite.AnyFunSuite
import xstream.util.Json
class ChatInterfaceContextSpec extends AnyFunSuite {
 test("UI context contains real controls, public Targets, numbered examples and filtered current state"){
  val targets=Json.mapper.createArrayNode();targets.addObject().put("value","local").put("label","Local").put("token","PRIVATE")
  val examples=Json.mapper.createArrayNode();val example=examples.addObject().put("id","sample").put("title","Sample analysis").put("job_yaml","name: Example\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: 'context:/data/item'}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: read}\n");example.putArray("requires").add("filter@2.0")
  val operators=Json.mapper.createArrayNode()
  val request=Json.obj().put("target","local").put("job_yaml","name: Draft\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: 'context:/data/item'}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: read}\n")
  val state=request.putObject("ui_state").put("library_tab","examples").put("details_tab","unknown").put("inspector_open",true).put("library_open",false).put("zoom_percent",115).put("logs_filter","error").put("credential","PRIVATE")
  val buttons=state.putArray("controls");buttons.addObject().put("id","duplicate-step").put("enabled",false);buttons.addObject().put("id","unknown-secret-control").put("enabled",true)
  state.putArray("selected_steps").add("read").add("nonexistent")
  val context=ChatInterfaceContext.build(targets,examples,operators,request)
  assert(context.path("capabilities").asText.contains("New") && context.path("capabilities").asText.contains("Choose design target"))
  assert(context.path("current_ui").path("library_tab").asText=="examples")
  assert(!context.path("current_ui").has("details_tab"))
  assert(context.path("current_ui").path("zoom_percent").asInt==115)
  assert(!context.path("current_ui").path("library_open").asBoolean)
  assert(context.path("current_ui").path("controls").size==1)
  assert(context.path("current_ui").path("selected_steps").size==1)
  assert(context.path("examples").get(0).path("number").asText=="01")
  assert(!context.path("examples").get(0).path("available_to_load").asBoolean)
  assert(!context.toString.contains("PRIVATE"));assert(!context.path("examples").get(0).has("job_yaml"))
  operators.addObject().put("name","filter").put("version","2.0")
  assert(ChatInterfaceContext.build(targets,examples,operators,request).path("examples").get(0).path("available_to_load").asBoolean)
 }
 test("interface guide covers every static interactive control and all major interaction areas"){
  val in=getClass.getResourceAsStream("/web/index.html");val html=try new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)finally in.close()
  val tags="""<(?:button|input|select|textarea|a)\b[^>]*>""".r
  val id="""\bid="([^"]+)"""".r
  val controls=tags.findAllIn(html).flatMap(tag=>id.findFirstMatchIn(tag).map(_.group(1))).toSet
  import scala.jdk.CollectionConverters._
  val covered=ChatInterfaceContext.features.elements.asScala.flatMap(_.path("control_ids").elements.asScala.map(_.asText)).toSet
  assert(controls.subsetOf(covered),"Missing interface descriptions: "+(controls--covered).mkString(", "))
  val sections=ChatInterfaceContext.features.elements.asScala.map(_.path("id").asText).toSet
  assert(Set("catalogs","examples","selection-deletion","ports-client-sink","parameter-editing","outputs-inference","variables","logs","export","confirmations","chat","dynamic-controls").subsetOf(sections))
 }

}
