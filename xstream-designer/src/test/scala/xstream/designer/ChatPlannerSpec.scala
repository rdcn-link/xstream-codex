package xstream.designer


import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.util.Json
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicReference
import com.fasterxml.jackson.databind.JsonNode

class TestChatPlugin extends ChatPlanner {
  def name="test-contributed-provider"
  def plan(request:JsonNode):JsonNode=request
}
class ChatPlannerSpec extends AnyFunSuite {
  test("compatible chat model receives current draft and history and returns a JSON proposal"){
    val observed=new AtomicReference[JsonNode]()
    val model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0)
    val proposal=Json.obj().put("reply","Renamed").put("job_yaml","name: Updated\nsteps: []\noutputs: {}\n").put("variables_yaml","N: 123\n")
    model.createContext("/v1/chat/completions",x=>{
      observed.set(Json.mapper.readTree(x.getRequestBody.readAllBytes()))
      val response=Json.obj();response.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("role","assistant").put("content",proposal.toString)
      val bytes=Json.mapper.writeValueAsBytes(response);x.sendResponseHeaders(200,bytes.length);x.getResponseBody.write(bytes);x.close()
    });model.start()
    try{
      val config=Json.obj().put("url",s"http://127.0.0.1:${model.getAddress.getPort}/v1/chat/completions").put("model","test-model")
      val planner=ChatPlanner.configured(config)
      val request=Json.obj().put("job_yaml","name: Old\nsteps: []\noutputs: {}\n").put("message","Rename the job");request.putArray("messages").addObject().put("role","user").put("content","Rename the job")
      request.putArray("operators");request.putArray("nodes")
      assert(planner.plan(request)==proposal)
      assert(observed.get.path("model").asText=="test-model")
      assert(observed.get.path("messages").get(0).path("role").asText=="system")
      assert(observed.get.path("messages").get(2).path("content").asText.contains("name: Old"))
      assert(observed.get.path("stream").asBoolean)
      assert(observed.get.path("messages").get(0).path("content").asText.contains("complete data-processing"))
      assert(observed.get.path("messages").get(0).path("content").asText.contains("ask focused questions"))
    }finally model.stop(0)
  }
  test("contributed planners are discovered through the shared ServiceLoader contract"){
    val planner=ChatPlanner.configured(Json.obj().put("provider","test-contributed-provider"))
    val request=Json.obj().put("message","test")
    assert(planner.plan(request)==request)
  }
  test("missing model configuration disables chat instead of interpreting commands"){
    val planner=ChatPlanner.configured(Json.obj())
    assert(planner.name=="disabled")
    assert(intercept[IllegalArgumentException](planner.plan(Json.obj().put("message","add anything"))).getMessage.contains("not configured"))
  }
  test("unknown external provider fails clearly"){
    intercept[IllegalArgumentException](ChatPlanner.configured(Json.obj().put("provider","not-installed")))
  }
  test("streamed model output reports progress, preserves Unicode and excludes private reasoning"){
    val model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0)
    val proposal=Json.obj().put("reply","可用的算子").putNull("job_yaml").putNull("variables_yaml")
    model.createContext("/stream",x=>{
      x.getRequestBody.readAllBytes();x.getResponseHeaders.set("Content-Type","text/event-stream");x.sendResponseHeaders(200,0)
      val out=x.getResponseBody
      def event(delta:JsonNode,finish:String=null):Unit={val e=Json.obj();val c=e.putArray("choices").addObject();c.set[JsonNode]("delta",delta);if(finish==null)c.putNull("finish_reason")else c.put("finish_reason",finish);out.write(("data: "+e.toString+"\n\n").getBytes(UTF_8));out.flush()}
      event(Json.obj().put("reasoning_content","PRIVATE_REASONING"))
      proposal.toString.grouped(7).foreach(chunk=>event(Json.obj().put("content",chunk)))
      event(Json.obj(),"stop");out.close();x.close()
    });model.start()
    try{
      val planner=new CompletionChat(Json.obj().put("url",s"http://127.0.0.1:${model.getAddress.getPort}/stream").put("model","test"))
      val progress=scala.collection.mutable.ArrayBuffer.empty[String]
      val replies=new StringBuilder
      val result=planner.plan(Json.obj().put("message","List operators"),progress+=_,replies.append(_))
      assert(result==proposal);assert(replies.toString==proposal.path("reply").asText);assert(progress.nonEmpty);assert(!progress.mkString.contains("PRIVATE_REASONING"))
    }finally model.stop(0)
  }
  test("model context contains operator contracts without runtime archives or download metadata"){
    val operators=Json.mapper.createArrayNode();operators.addObject().put("name","sample").put("version","1.0").put("archive","large").putObject("parameters").put("type","object")
    val compact=ChatPlanner.operatorContext(operators).get(0)
    assert(compact.path("name").asText=="sample"&&compact.has("parameters") && !compact.has("archive"))
  }

}
