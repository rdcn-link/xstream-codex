package xstream.designer


import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.Json
import java.net.URI
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._

/** Model adapters are independently installable with Java ServiceLoader. */
trait ChatPlanner {
  def name:String
  def plan(request:JsonNode):JsonNode
  def plan(request:JsonNode,progress:String=>Unit):JsonNode=plan(request)
  def plan(request:JsonNode,progress:String=>Unit,replyDelta:String=>Unit):JsonNode=plan(request,progress)
}
object ChatPlanner {
  val instructions="""Help users design XStream streaming jobs through conversation. Accept complete data-processing and analysis requirements, not command syntax.
Return a JSON object with reply, job_yaml and variables_yaml. Reply in the user's language.
The server supplies designer_interface describing real UI controls, configured Targets, numbered Examples and the current selection/panels. Use it to answer interface questions with exact button names. Never claim to operate the UI or invent controls. UI-only instructions should have null job_yaml and variables_yaml.
If requirements are ambiguous, ask focused questions in reply and return null for job_yaml and variables_yaml. Do not fabricate dataset paths, operators or missing capabilities.
When sufficient information is available, propose a complete end-to-end job and typed variables, explaining assumptions, inputs, transformations and the final output.
A request may replace the whole job when the user asks for a new analysis.
Preserve the current draft unless asked to replace it. Job fields: name,steps; optional designer holds presentation metadata and does not affect execution. Preserve designer metadata when modifying existing steps. Step fields: id,title,description,operator,inputs,params. Optional description is multiline documentation, up to 8192 characters, and does not affect execution.
The server supplies server_catalogs with automatically browsed dataset and Item metadata for the current Target and configured remote catalogs, plus operator metadata from repository_url. Server-side snapshots may be reused until their cache TTL expires; respect the supplied collection time. These are available without the user expanding the sidebar. Use these inventories to answer questions about available data and operators. Browser catalog_items may supplement deeper paths.
Catalog status, complete and truncated flags are authoritative: unavailable or incomplete catalogs do not mean no data. Never claim a bounded inventory is exhaustive. Ask for a deeper path when needed. Use Item uri exactly as supplied: context: is relative to the selected Target; explicit dacp: URIs select another configured catalog. Choose non-folder Items and one advertised view. Do not claim to have read data contents.
Use only available versioned operators. Their static parameters definitions declare permitted names, types and constraints; follow these declarations. Inputs reference step[:port]; default port is result. No depends_on or inline dataset bindings.
Use item_source@1.0 with empty inputs and params.uri (context:/dataset/item for the current DataCatalog, or dacp://host:port/dataset/item for an explicit service); optional params.sql queries FROM source and requires records view for data sources. Source generators also have empty inputs.
Design a logical stream graph without execution placement. Use public dataset URIs from the configured DACP service.
An output has at most one consumer. End each returned-result branch with an explicit client_sink@1.0 step whose inputs map result labels to upstream step[:port] bindings. There is no top-level outputs field. Explicit Sink operators may terminate branches without a client_sink.
Variables are complete values like $INPUT_URI, with typed values supplied in a separate YAML mapping.
Do not invent schemas. Inference is a separate metadata request from the editor. Never run jobs or claim they ran."""
  def operatorContext(operators:JsonNode):JsonNode={
    val result=Json.mapper.createArrayNode()
    operators.elements.asScala.foreach{operator=>
      val compact=Json.obj()
      Seq("name","version","description","inputs","outputs","parameters").foreach(key=>Option(operator.get(key)).foreach(value=>compact.set[JsonNode](key,value)))
      result.add(compact)
    };result
  }
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
  protected def exchange(body:JsonNode,progress:String=>Unit,replyDelta:String=>Unit):JsonNode={
    val builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json")
    Option(config.get("token_env")).foreach{n=>val token=sys.env.getOrElse(n.asText,throw new IllegalArgumentException("Chat adapter token is not configured"));builder.header("Authorization","Bearer "+token)}
    val response=client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(Json.mapper.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofInputStream())
    val in=new java.io.BufferedInputStream(response.body())
    try {
      require(response.statusCode()==200,"Chat adapter request failed")
      if(!response.headers.firstValue("Content-Type").orElse("").contains("text/event-stream")){
        val bytes=in.readNBytes(1024*1024+1);require(bytes.length<=1024*1024,"Chat response exceeds 1 MiB");Json.mapper.readTree(bytes)
      }else{
        val replyStream=new ChatReplyStream(replyDelta)
        val content=new StringBuilder;val line=new java.io.ByteArrayOutputStream();val data=new StringBuilder
        var bytes=0;var finished=false;var finishReason="";var announced=false;var lastProgress=0L
        def consume():Unit={
          val text=data.toString.trim;data.clear()
          if(text=="[DONE]")finished=true
          else if(text.nonEmpty){
            val event=Json.mapper.readTree(text);require(!event.has("error"),"Model streaming request failed")
            val choice=event.path("choices").path(0);val delta=choice.path("delta").path("content")
            if(delta.isTextual){content.append(delta.asText);replyStream.feed(delta.asText)}
            if(!choice.path("finish_reason").isMissingNode && !choice.path("finish_reason").isNull){finishReason=choice.path("finish_reason").asText;finished=true}
            val now=System.nanoTime()
            if(!announced || now-lastProgress>java.util.concurrent.TimeUnit.SECONDS.toNanos(1)){
              progress(if(content.nonEmpty)s"Receiving model response · ${content.length} characters"else "Model connected; preparing response")
              announced=true;lastProgress=now
            }
          }
        }
        def consumeLine():Unit={
          val text=line.toString(java.nio.charset.StandardCharsets.UTF_8).stripSuffix("\r");line.reset()
          if(text.isEmpty)consume()else if(text.startsWith("data:")){if(data.nonEmpty)data.append('\n');data.append(text.stripPrefix("data:").stripPrefix(" "))}
        }
        var next=in.read()
        while(next>=0 && !finished){
          bytes+=1;require(bytes<=1024*1024,"Chat response exceeds 1 MiB")
          if(next==10)consumeLine()else line.write(next)
          if(!finished)next=in.read()
        }
        if(!finished){if(line.size>0)consumeLine();if(data.nonEmpty)consume()}
        require(finished,"Model response stream ended prematurely");require(finishReason!="length","Model response was truncated")
        val result=Json.obj();result.putArray("choices").addObject().put("finish_reason",finishReason).putObject("message").put("content",content.toString);result
      }
    }finally in.close()
  }
}
/** Standard chat-completions adapter, usable with self-hosted compatible model services. */
final class CompletionChat(config:JsonNode) extends HttpChat(config) {
  override def name="openai-compatible"
  require(config.path("model").asText.trim.nonEmpty,"Configure a chat model")
  override def plan(request:JsonNode):JsonNode=plan(request,_=>())
  override def plan(request:JsonNode,progress:String=>Unit):JsonNode=plan(request,progress,_=>())
  override def plan(request:JsonNode,progress:String=>Unit,replyDelta:String=>Unit):JsonNode={
    val body=Json.obj().put("model",config.path("model").asText).put("stream",true)
    val messages=body.putArray("messages")
    messages.addObject().put("role","system").put("content",ChatPlanner.instructions+" Return only one JSON object, without markdown fences.")
    request.path("messages").elements.asScala.toVector.takeRight(12).foreach{m=>
      val role=m.path("role").asText
      require(Set("user","assistant")(role) && m.path("content").isTextual,"Invalid chat history")
      messages.addObject().put("role",role).put("content",m.path("content").asText)
    }
    val context=request.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();context.remove("messages")
    messages.addObject().put("role","user").put("content","Current draft, available operators, and request:\n"+context.toString)
    val response=exchange(body,progress,replyDelta);val choice=response.path("choices").path(0)
    require(choice.path("finish_reason").asText!="length","Model response was truncated")
    val content=choice.path("message").path("content")
    require(content.isTextual,"Model returned no text proposal")
    val text=content.asText.trim.stripPrefix("```json").stripPrefix("```").stripSuffix("```").trim
    Json.mapper.readTree(text)
  }
}
