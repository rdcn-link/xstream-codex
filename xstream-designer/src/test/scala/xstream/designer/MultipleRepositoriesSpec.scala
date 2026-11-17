package xstream.designer

import org.scalatest.funsuite.AnyFunSuite
import xstream.util.Json
import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress,URI}
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters._

class MultipleRepositoriesSpec extends AnyFunSuite {
  test("catalog merges repositories, preserves versions and gives the first repository precedence") {
    def operator(name:String,version:String,description:String)=Json.mapper.readTree(s"""{"name":"$name","version":"$version","description":"$description","icon":{"path":"M3 5h18v14H3z"},"parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false}}""")
    def repo(items:Seq[com.fasterxml.jackson.databind.JsonNode])={
      val server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0)
      server.createContext("/v1/operators",x=>{val bytes=Json.mapper.writeValueAsBytes(items.asJava);x.sendResponseHeaders(200,bytes.length);x.getResponseBody.write(bytes);x.close()})
      server.start();server
    }
    val first=repo(Seq(operator("shared","1.0","first"),operator("only_first","1.0","first")))
    val second=repo(Seq(operator("shared","1.0","second"),operator("shared","2.0","second"),operator("only_second","1.0","second")))
    val observed=new java.util.concurrent.atomic.AtomicReference[String]()
    first.createContext("/completions",x=>{
      observed.set(new String(x.getRequestBody.readAllBytes(),UTF_8))
      val response=Json.obj()
      response.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("content",Json.obj().put("reply","Which data should I use?").putNull("job_yaml").putNull("variables_yaml").toString)
      val bytes=Json.mapper.writeValueAsBytes(response);x.sendResponseHeaders(200,bytes.length);x.getResponseBody.write(bytes);x.close()
    })
    val config=Json.obj().put("host","127.0.0.1").put("port",0)
    config.putObject("chat").put("url",s"http://127.0.0.1:${first.getAddress.getPort}/completions").put("model","test-model")
    config.putArray("remote_operator_library_urls").add(s"http://127.0.0.1:${first.getAddress.getPort}").add(s"http://127.0.0.1:${second.getAddress.getPort}")
    val site=new Designer(config);site.start()
    val client=HttpClient.newHttpClient()
    def request(body:String)=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}/api/operators")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    try {
      val response=request("{}")
      assert(response.statusCode()==200,response.body())
      val items=Json.mapper.readTree(response.body()).elements.asScala.toVector
      assert(items.map(o=>o.path("name").asText+"@"+o.path("version").asText)==Vector("shared@1.0","only_first@1.0","shared@2.0","only_second@1.0"))
      assert(items.head.path("description").asText=="first")
      assert(request("""{"remote_operator_library_urls":["http://attacker.invalid"]}""").statusCode()==400)
      val chat=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}/api/chat")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.obj().put("job_yaml","name: Draft\nsteps: []\n").put("variables_yaml","{}").put("message","What operators are available?").toString)).build(),HttpResponse.BodyHandlers.ofString())
      assert(chat.statusCode()==200,chat.body())
      assert(observed.get.contains("only_first") && observed.get.contains("only_second") && observed.get.contains("repository_urls"),observed.get)
      second.stop(0)
      assert(request("{}").statusCode()!=200,"An unavailable repository must not silently disappear")
    } finally { site.close();first.stop(0);second.stop(0) }
  }
  test("package lookup keeps configured precedence over a later repository cache and only falls through on 404") {
    val root=java.nio.file.Files.createTempDirectory("repository-order-")
    val first=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0)
    val calls=new java.util.concurrent.atomic.AtomicInteger()
    val status=new java.util.concurrent.atomic.AtomicInteger(200)
    first.createContext("/v1/operators/shared/1.0",x=>{
      calls.incrementAndGet()
      val bytes="{}".getBytes(UTF_8);x.sendResponseHeaders(status.get,bytes.length);x.getResponseBody.write(bytes);x.close()
    });first.start()
    val urls=Seq(s"http://127.0.0.1:${first.getAddress.getPort}","http://127.0.0.1:1")
    val hash=java.security.MessageDigest.getInstance("SHA-256").digest(urls(1).getBytes(UTF_8)).map(b=>f"${b & 0xff}%02x").mkString
    val cached=root.resolve(s"cache/manifests/$hash/shared/1.0.json")
    java.nio.file.Files.createDirectories(cached.getParent);java.nio.file.Files.writeString(cached,"{}")
    val plugins=new xstream.data.PluginEnvironment()
    val env=new xstream.operator.inference.OperatorInferenceService(root.resolve("local"),urls,root.resolve("cache"),1024,plugins)
    try {
      val invalid=intercept[IllegalArgumentException](xstream.operator.inference.OperatorLibrary.resolve(env,"shared@1.0",null))
      assert(invalid.getMessage.contains("operator identity"))
      assert(calls.get==1,"The earlier repository must be consulted before the later cached manifest")
      status.set(404)
      intercept[IllegalArgumentException](xstream.operator.inference.OperatorLibrary.resolve(env,"shared@1.0",null))
      assert(calls.get==2,"A missing operator must allow lookup to reach the later cache")
      status.set(503)
      val unavailable=intercept[Exception](xstream.operator.inference.OperatorLibrary.resolve(env,"shared@1.0",null))
      assert(!Option(unavailable.getMessage).getOrElse("").contains("operator identity"),"HTTP errors must not fall through to cached alternatives")
    } finally {env.close();plugins.close();first.stop(0);xstream.data.Data.deleteTree(root)}
  }
  test("repository configuration rejects missing, legacy and unsafe entries") {
    for(value<-Seq("{}","{\"repository\":{\"url\":\"http://localhost\"}}","{\"remote_operator_library_urls\":[]}","{\"remote_operator_library_urls\":[\"http://user:secret@localhost\"]}","{\"remote_operator_library_urls\":[\"file:///tmp/repo\"]}")) {
      intercept[IllegalArgumentException](new Designer(Json.mapper.readTree(value)))
    }
  }
}
