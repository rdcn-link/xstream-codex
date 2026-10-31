package xstream.designer


import com.fasterxml.jackson.databind.JsonNode
import dacp.union.Json
import java.net.URI
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._

/** Model adapters are independently installable with Java ServiceLoader. */
trait ChatPlanner {
  def name:String
  def plan(request:JsonNode):JsonNode
}
object ChatPlanner {
  val instructions="""Help users design XStream streaming jobs through conversation. Accept complete data-processing and analysis requirements, not command syntax.
Return a JSON object with reply, job_yaml and variables_yaml. Reply in the user's language.
If requirements are ambiguous, ask focused questions in reply and return null for job_yaml and variables_yaml. Do not fabricate dataset paths, operators or missing capabilities.
When sufficient information is available, propose a complete end-to-end job and typed variables, explaining assumptions, inputs, transformations and the final output.
A request may replace the whole job when the user asks for a new analysis.
Preserve the current draft unless asked to replace it. Job fields: name,tasks,outputs; optional designer holds presentation metadata and does not affect execution. Preserve designer metadata when modifying existing steps. Step fields: id,title,operator,inputs,params.
Use only available versioned operators. Their static parameters definitions declare permitted names, types and constraints; follow these declarations. Inputs reference task[:port]; default port is result. No depends_on or inline dataset bindings.
Use read_item@1.0 with empty inputs and params.uri (context:/dataset/item for the current DataCatalog, or dacp://host:port/dataset/item for an explicit service); optional params.sql queries FROM source and requires records view for data sources. Source generators also have empty inputs.
Design a logical stream graph without execution placement. Use public dataset URIs from the configured DACP service.
An output has at most one consumer. The Job Outlet accepts at most one step output port. Empty job outputs and sink operators are permitted.
Variables are complete values like $INPUT_URI, with typed values supplied in a separate YAML mapping.
Do not invent schemas. Inference is a separate metadata request from the editor. Never run jobs or claim they ran."""
  def configured(config:JsonNode):ChatPlanner={
    val provider=config.path("provider").asText("openai-compatible")
    if(config.isMissingNode || config.isNull || config.isEmpty) new ChatPlanner {
      val name="disabled"
      def plan(request:JsonNode):JsonNode=throw new IllegalArgumentException("Chat is not configured. Configure chat.url and chat.model on the Designer server.")
    }
    else if(provider=="openai-compatible")new CompletionChat(config)
    else java.util.ServiceLoader.load(classOf[ChatPlanner]).iterator.asScala.find(_.name==provider).getOrElse(throw new IllegalArgumentException("Chat provider not installed: "+provider))
  }
}
abstract class HttpChat(config:JsonNode) extends ChatPlanner {
  private val endpoint=URI.create(config.path("url").asText)
  require(Set("http","https")(endpoint.getScheme) && endpoint.getHost!=null,"Expected chat adapter HTTP URL")
  private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  protected def exchange(body:JsonNode):JsonNode={
    val builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json")
    Option(config.get("token_env")).foreach{n=>val token=sys.env.getOrElse(n.asText,throw new IllegalArgumentException("Chat adapter token is not configured"));builder.header("Authorization","Bearer "+token)}
    val response=client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(Json.mapper.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofInputStream())
    val in=response.body();val bytes=try in.readNBytes(1024*1024+1)finally in.close()
    require(response.statusCode()==200,"Chat adapter request failed")
    require(bytes.length<=1024*1024,"Chat response exceeds 1 MiB");Json.mapper.readTree(bytes)
  }
}
/** Standard chat-completions adapter, usable with self-hosted compatible model services. */
final class CompletionChat(config:JsonNode) extends HttpChat(config) {
  override def name="openai-compatible"
  require(config.path("model").asText.trim.nonEmpty,"Configure a chat model")
  override def plan(request:JsonNode):JsonNode={
    val body=Json.obj().put("model",config.path("model").asText).put("stream",false)
    val messages=body.putArray("messages")
    messages.addObject().put("role","system").put("content",ChatPlanner.instructions+" Return only one JSON object, without markdown fences.")
    request.path("messages").elements.asScala.toVector.takeRight(12).foreach{m=>
      val role=m.path("role").asText
      require(Set("user","assistant")(role) && m.path("content").isTextual,"Invalid chat history")
      messages.addObject().put("role",role).put("content",m.path("content").asText)
    }
    val context=request.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();context.remove("messages")
    messages.addObject().put("role","user").put("content","Current draft, available operators, and request:\n"+context.toString)
    val response=exchange(body);val choice=response.path("choices").path(0)
    require(choice.path("finish_reason").asText!="length","Model response was truncated")
    val content=choice.path("message").path("content")
    require(content.isTextual,"Model returned no text proposal")
    val text=content.asText.trim.stripPrefix("```json").stripPrefix("```").stripSuffix("```").trim
    Json.mapper.readTree(text)
  }
}
