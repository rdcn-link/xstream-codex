package xstream.designer

import _root_.xstream.data._

import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.util.{Json,YamlDocument}
import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress,URI}
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters._

class DesignerSpec extends AnyFunSuite {
  test("Client Sink retains its identity, description and normal step layout; only drafts may be disconnected") {
    val job="""name: Return data
      |steps:
      |  - id: read
      |    operator: item_source@1.0
      |    inputs: {}
      |    params: {uri: 'context:/files/input.txt', view: bytes}
      |  - id: receive
      |    title: Client Sink
      |    description: Return the selected data to this caller
      |    operator: client_sink@1.0
      |    inputs: {data: 'read:result'}
      |designer:
      |  version: 1
      |  positions: {receive: {x: 600, y: 200}}
      |""".stripMargin
    val parsed=Documents.job(job)
    assert(Documents.job(YamlDocument.dump(parsed))==parsed)
    assert(parsed.path("steps").get(1).path("id").asText=="receive")
    assert(!parsed.has("outputs"))
    val disconnected=job.replace("inputs: {data: 'read:result'}","inputs: {}")
    Documents.job(disconnected,true)
    intercept[IllegalArgumentException](Documents.job(disconnected))
    intercept[IllegalArgumentException](Documents.job(job+"outputs: {data: read}\n"))
    intercept[IllegalArgumentException](Documents.job(job.replace("positions: {receive: {x: 600, y: 200}}","client_sink: {x: 600, y: 200}")))
  }
  test("inline definitions survive authoring export and are excluded from remote Targets") {
    val local="""name: Local script
      |operators:
      |  upper:
      |    type: script
      |    role: transform
      |    runtime: python_tools
      |    interpreter: /usr/local/bin/python
      |    script: {source: 'print(1)'}
      |    inputs: {source: {view: bytes, binding: stdin}}
      |    outputs: {result: {view: bytes, binding: stdout, format: text}}
      |steps:
      |  - id: read
      |    operator: item_source@1.0
      |    inputs: {}
      |    params: {uri: 'context:/data/input.txt', view: bytes}
      |  - id: upper
      |    operator: {inline: upper}
      |    inputs: {source: read}
      |  - id: client_sink
      |    operator: client_sink@1.0
      |    inputs: {result: upper:result}
      |""".stripMargin
    val document=Documents.job(local)
    assert(document.path("operators").path("upper").path("type").asText=="script")
    val exported=YamlDocument.dump(document)
    assert(Documents.job(exported)==document)
    intercept[IllegalArgumentException](_root_.xstream.execution.RemoteJobPolicy.validateYaml(exported))
    intercept[IllegalArgumentException](Documents.job(local.replace("inline: upper","inline: missing")))
  }
  val workflow="""name: Weather
                  |steps:
                  |  - id: read
                  |    operator: item_source@1.0
                  |    inputs: {}
                  |    params: {uri: $INPUT_URI, view: bytes}
                  |  - id: parse
                  |    operator: piflow_csv_parser@1.0
                  |    inputs: {source: read}
                  |  - id: client_sink
                  |    operator: client_sink@1.0
                  |    inputs: {result: parse}
                  |""".stripMargin
  test("packaged examples are complete, variable-resolved Jobs with one Client Sink") {
    val examples=ExampleJobs.all
    assert(examples.size>=5)
    val ids=examples.elements.asScala.map(_.path("id").asText).toVector
    assert(ids.distinct==ids)
    examples.elements.asScala.foreach { e=>
      val source=e.path("job_yaml").asText;val variables=Documents.variables(e.path("variables_yaml").asText)
      val doc=Documents.job(xstream.util.WorkflowVariables.resolve(source,variables))
      assert(doc.path("steps").elements.asScala.count(_.path("operator").asText=="client_sink@1.0")==1 && doc.path("steps").size==e.path("step_count").asInt)
      assert(!doc.has("designer") || doc.path("designer").has("target"))
    }
    assert(examples.elements.asScala.find(_.path("id").asText=="variables").get.path("variables_yaml").asText.contains("INCLUDE_HEADER: true"))
  }
  test("deployment examples load from a bounded directory and validate resource names") {
    val dir=java.nio.file.Files.createTempDirectory("designer-examples-")
    try {
      java.nio.file.Files.writeString(dir.resolve("index.yaml"),"- id: actual\n  title: Actual catalog\n  description: Deployment data\n  job: actual.yaml\n  variables: values.yaml\n")
      java.nio.file.Files.writeString(dir.resolve("actual.yaml"),workflow.replace("$INPUT_URI","context:/actual/observations"))
      java.nio.file.Files.writeString(dir.resolve("values.yaml"),"{}")
      val result=ExampleJobs.load(Some(dir))
      assert(result.size==1 && result.get(0).path("job_yaml").asText.contains("context:/actual/observations"))
      val index=java.nio.file.Files.readString(dir.resolve("index.yaml"))
      java.nio.file.Files.writeString(dir.resolve("index.yaml"),index+"  tags: [Variables, Variables, ' Remote target ']\n")
      assert(ExampleJobs.load(Some(dir)).get(0).path("tags").elements.asScala.map(_.asText).toVector==Vector("Variables","Remote target"))
      java.nio.file.Files.writeString(dir.resolve("index.yaml"),index+"  tags: [123]\n")
      intercept[IllegalArgumentException](ExampleJobs.load(Some(dir)))
      java.nio.file.Files.writeString(dir.resolve("index.yaml"),"- id: bad\n  title: Bad\n  description: Bad\n  job: ../outside.yaml\n  variables: values.yaml\n")
      intercept[IllegalArgumentException](ExampleJobs.load(Some(dir)))
    } finally {
      val files=java.nio.file.Files.list(dir);try files.iterator.asScala.foreach(java.nio.file.Files.delete(_))finally files.close()
      java.nio.file.Files.delete(dir)
    }
  }
  test("cross-node examples include two and three sources and saved design targets") {
    val examples=ExampleJobs.all.elements.asScala.map(e=>e.path("id").asText->e).toMap
    def resolved(id:String)={val e=examples(id);Documents.job(xstream.util.WorkflowVariables.resolve(e.path("job_yaml").asText,Documents.variables(e.path("variables_yaml").asText)))}
    for((id,count)<-Seq("two-nodes"->2,"three-nodes"->3)){
      val j=resolved(id)
      val sources=j.path("steps").elements.asScala.filter(_.path("operator").asText=="item_source@1.0").toVector
      assert(sources.size==count)
      assert(sources.map(s=>URI.create(s.path("params").path("uri").asText).getHost).distinct.size==count)
      assert(j.path("designer").path("target").asText=="local")
    }
    assert(examples("three-nodes").path("tags").elements.asScala.map(_.asText).toSet.contains("Cross-node"))
    assert(examples("variables").path("tags").elements.asScala.map(_.asText).toSet.contains("Variables"))
    assert(examples("remote-target").path("tags").elements.asScala.map(_.asText).toSet.contains("Remote target"))
    val union=resolved("union-collaboration")
    assert(union.path("designer").path("target").asText=="dacp://union.example.org:19860")
    assert(union.path("steps").elements.asScala.filter(_.path("operator").asText=="item_source@1.0").forall(_.path("params").path("uri").asText.startsWith("context:/")))
    assert(resolved("remote-target").path("designer").path("target").asText=="dacp://node-a.example.org:18861")
  }
  test("step descriptions are multiline job metadata and round-trip through YAML") {
    val annotated=workflow.replace("    operator: item_source@1.0", "    description: |\n      Read local observations.\n      Keep original units.\n    operator: item_source@1.0")
    val document=Documents.job(annotated)
    val text=document.path("steps").get(0).path("description").asText
    assert(text=="Read local observations.\nKeep original units.\n")
    assert(Documents.job(YamlDocument.dump(document)).path("steps").get(0).path("description").asText==text)
    intercept[IllegalArgumentException](Documents.job(annotated.replace("description: |\n      Read local observations.\n      Keep original units.","description: 123")))
  }
  test("showcase configuration exposes a validated default draft without exposing configuration paths") {
    val dir=java.nio.file.Files.createTempDirectory("designer-showcase")
    val jobFile=dir.resolve("demo.yaml");val varsFile=dir.resolve("vars.yaml")
    java.nio.file.Files.writeString(jobFile,workflow);java.nio.file.Files.writeString(varsFile,"INPUT_URI: context:/weather/demo.csv\nN: 123\n")
    val config=Json.obj().put("host","127.0.0.1").put("port",0)
    config.putArray("remote_operator_library_urls").add("http://localhost:8820")
    config.putObject("showcase").put("job_file",jobFile.toString).put("variables_file",varsFile.toString).put("label","Load demo")
    val site=new Designer(config);site.start()
    try {
      val response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}/api/config")).build(),HttpResponse.BodyHandlers.ofString())
      val data=Json.mapper.readTree(response.body())
      assert(data.path("showcase").path("job_yaml").asText==workflow)
      assert(Documents.variables(data.path("showcase").path("variables_yaml").asText).path("N").asInt==123)
      assert(data.path("draft_key").asText=="xstream-designer-showcase")
      assert(!response.body().contains(dir.toString))
    } finally {site.close();java.nio.file.Files.delete(jobFile);java.nio.file.Files.delete(varsFile);java.nio.file.Files.delete(dir)}
  }
  test("catalog credentials match origins independently of dataset paths") {
    assert(CatalogConnections.origin("dacp://localhost:18861/weather")=="dacp://localhost:18861")
    assert(CatalogConnections.origin("dacp://localhost:18861/")=="dacp://localhost:18861")
    intercept[IllegalArgumentException](CatalogConnections.origin("dacp://user@localhost:18861/weather"))
    intercept[IllegalArgumentException](CatalogConnections.origin("dacp://localhost:18861/weather?token=secret"))
  }
  test("Client Sink rejects multiple result bindings"){
    val error=intercept[IllegalArgumentException](Documents.job(workflow.replace("inputs: {result: parse}","inputs: {result: parse, second: read}")))
    assert(error.getMessage.contains("exactly one input"))
  }
  test("round-trip YAML, typed variables, logical stream graphs and sinks"){
    val j=Documents.job(workflow);assert(Documents.job(YamlDocument.dump(j))==j)
    val vars=Documents.variables("N: 123\nENABLED: true\nNAME: '001'\n")
    assert(vars.path("N").isInt && vars.path("ENABLED").isBoolean && vars.path("NAME").asText=="001")
    intercept[IllegalArgumentException](Documents.job(workflow.replace("inputs: {result: parse}","inputs: {result: read}" )))
    assert(Documents.job("name: Empty\nsteps: []\n",true).path("steps").size()==0)
    intercept[IllegalArgumentException](Documents.job("name: Empty\nsteps: []\n"))
  }
  test("designer layout and provenance round-trip and reject invalid metadata"){
    val text=workflow+"""designer:
      |  version: 1
      |  generator: {name: XStream Designer, version: 0.1.0-SNAPSHOT}
      |  exported_at: '2026-10-28T12:34:56Z'
      |  positions: {read: {x: 120, y: 250}, parse: {x: 600, y: 90}}
      |  viewport: {zoom: 0.8, scroll_left: 100, scroll_top: 200}
      |""".stripMargin
    val parsed=Documents.job(text)
    assert(Documents.job(YamlDocument.dump(parsed))==parsed)
    intercept[IllegalArgumentException](Documents.job(text.replace("2026-10-28T12:34:56Z","yesterday")))
    intercept[IllegalArgumentException](Documents.job(text.replace("zoom: 0.8","zoom: 0")))
  }
  test("reject cycles, duplicate consumers, object bindings, obsolete dependencies and unsafe YAML"){
    intercept[IllegalArgumentException](Documents.job(workflow.replace("source: read","source: parse")))
    intercept[IllegalArgumentException](Documents.job(workflow.replace("result: parse","result: read")))
    intercept[IllegalArgumentException](Documents.job(workflow.replace("source: read","source: {task: read}")))
    intercept[IllegalArgumentException](Documents.job(workflow.replace("    operator:","    depends_on: [read]\n    operator:")))
    intercept[IllegalArgumentException](Documents.variables("x: !!java.net.URL ['https://example.com']"))
    intercept[IllegalArgumentException](Documents.variables("x: 1\nx: 2\n"))
  }
  test("authoring rejects member placement and node-region metadata") {
    intercept[IllegalArgumentException](Documents.job(workflow.replace("    operator:","    node: nd-private\n    operator:")))
    intercept[IllegalArgumentException](Documents.job(workflow+"designer:\n  version: 1\n  node_areas: {}\n"))
  }
  test("schema preview decodes real Arrow fields and preserves explicit unknowns"){
    val schema=org.apache.arrow.vector.types.pojo.Schema.fromJSON("""{"fields":[{"name":"temperature","nullable":true,"type":{"name":"floatingpoint","precision":"DOUBLE"},"children":[]}]}""")
    val report=Json.obj();val outputs=report.putObject("outputs");outputs.putObject("result").put("schema",java.util.Base64.getEncoder.encodeToString(schema.serializeAsMessage())).put("certainty","known");outputs.putObject("unknown").put("certainty","unknown")
    val preview=SchemaPreview.present(report)
    assert(preview.path("outputs").path("result").path("schema_json").path("fields").get(0).path("name").asText=="temperature")
    assert(!preview.path("outputs").path("unknown").has("schema_json"))
    assert(!report.path("outputs").path("result").has("schema_json"))
  }
  test("design Target is validated designer metadata, retained on export") {
    val job=Documents.mapping(workflow).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    val metadata=job.putObject("designer").put("version",1).put("target","dacp://node-a:18861")
    assert(Documents.job(job.toString).path("designer").path("target").asText=="dacp://node-a:18861")
    metadata.put("target","local");Documents.job(job.toString)
    for(value<-Seq("https://example.com","dacp://user:secret@node-a:18861","dacp://node-a:18861/weather","")){
      metadata.put("target",value);intercept[IllegalArgumentException](Documents.job(job.toString))
    }
  }
  test("public HTTP editor, export and unconfigured model chat without leaking credentials"){
    val repo=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0)
    repo.createContext("/v1/operators",x=>{val bytes="""[{"name":"piflow_csv_parser","version":"1.0","icon":{"path":"M3 5h18v14H3z"},"parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false},"inputs":{"source":{"representations":["bytes"]}}},{"name":"piflow_filter","version":"1.0","icon":{"path":"M3 5h18v14H3z"},"parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false},"inputs":{"source":{"representations":["records"]}}}]""".getBytes(UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody.write(bytes);x.close()});repo.start()
    val config=Documents.mapping(s"""host: 127.0.0.1
                                  |port: 0
                                  |nodes:
                                  |  - {url: 'dacp://localhost:18861', token_file: '/private/secret.token'}
                                  |remote_operator_library_urls:
                                  |  - 'http://127.0.0.1:${repo.getAddress.getPort}'
                                  |""".stripMargin)
    val site=new Designer(config);site.start();val client=HttpClient.newHttpClient()
    def get(path:String)=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}"+path)).GET().build(),HttpResponse.BodyHandlers.ofString())
    def post(path:String,body:com.fasterxml.jackson.databind.JsonNode)=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString)).build(),HttpResponse.BodyHandlers.ofString())
    try{
      assert(get("/").body().contains("id=\"outputs-tab\""));assert(!get("/").body().contains("id=\"infer\""));assert(!get("/").body().contains("id=\"tab-schema\""));assert(!get("/api/config").body().contains("secret.token"))
      assert(get("/").body().contains("src=\"yaml-editor.js\""))
      val editor=get("/yaml-editor.js")
      assert(editor.statusCode()==200 && editor.body().contains("CodeMirror"))
      assert(!editor.body().contains("https://esm.sh"))
      assert(get("/editor-licenses.txt").body().contains("@codemirror/lang-yaml"))
      val export=post("/api/export",Json.obj().put("job_yaml",workflow).put("variables_yaml","N: 123"))
      assert(export.statusCode()==200);val exported=Documents.job(Json.mapper.readTree(export.body()).path("job_yaml").asText)
      val layout=exported.path("designer")
      assert(layout.path("generator").path("name").asText=="XStream Designer")
      assert(layout.path("generator").path("version").asText==DesignerLayout.softwareVersion)
      java.time.Instant.parse(layout.path("exported_at").asText)
      assert(exported.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].remove("designer")!=null)
      assert(exported==Documents.job(workflow))
      val plain=post("/api/export",Json.obj().put("job_yaml",workflow).put("variables_yaml","{}").put("include_designer",false))
      assert(plain.statusCode()==200)
      assert(!Documents.job(Json.mapper.readTree(plain.body()).path("job_yaml").asText).has("designer"))
      val request=Json.obj().put("job_yaml",workflow.replace("    node: nd-abcd-efgh\n","")).put("variables_yaml","{}").put("message","add piflow_filter@1.0")
      val response=post("/api/chat",request);assert(response.statusCode()==400,response.body())
      assert(response.body().contains("Chat is not configured"))
      assert(get("/api/config").body().contains("disabled"))
      val targets=Json.mapper.readTree(get("/api/config").body()).path("targets")
      assert(targets.get(0).path("value").asText=="local" && targets.size==2)
      val roots=post("/api/catalogs",Json.obj().put("target","local"))
      assert(roots.statusCode()==200 && Json.mapper.readTree(roots.body()).size==2)
      val selected=post("/api/catalogs",Json.obj().put("target","dacp://localhost:18861"))
      assert(selected.statusCode()==200 && Json.mapper.readTree(selected.body()).size==1)
      assert(post("/api/catalogs",Json.obj().put("target","dacp://unconfigured:1234")).statusCode()==400)
      assert(post("/api/catalogs",Json.obj().set[com.fasterxml.jackson.databind.JsonNode]("target",Json.obj().put("token","secret"))).statusCode()==400)

      assert(post("/api/nodes",Json.obj()).statusCode()==400)
      assert(post("/api/connect",Json.obj()).statusCode()==400)
      assert(post("/api/operators",Json.obj().put("repository","ops")).statusCode()==400)
      assert(post("/api/nodes",Json.obj().put("target","local")).statusCode()==400)
      assert(!get("/").body().contains("id=\"toggle-connections\""))
      assert(get("/../build.sbt").statusCode()==404)
      assert(post("/api/submit",Json.obj()).statusCode()==400)
    }finally{site.close();repo.stop(0)}
  }
  test("local catalog browsing and inference work without a DACP target; remote roots stay lazy") {
    val dir=java.nio.file.Files.createTempDirectory("designer-local-catalog-")
    java.nio.file.Files.createDirectories(dir.resolve("nested"))
    java.nio.file.Files.writeString(dir.resolve("nested/data.csv"),"station,temperature\nA,12\nB,15\n")
    val archive=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("geotrans-input.zip")))
    try {archive.putNextEntry(new java.util.zip.ZipEntry("labels/points.txt"));archive.write("points".getBytes(UTF_8));archive.closeEntry()}finally archive.close()
    val config=Documents.mapping(s"""host: 127.0.0.1
      |port: 0
      |remote_operator_library_urls: ['http://127.0.0.1:1']
      |nodes:
      |- {url: 'dacp://127.0.0.1:1', token_file: '/private/not-exposed.token'}
      |local_catalog:
      |  datasets:
      |    weather:
      |      provider: directory
      |      source: {root: '${dir.toString}'}
      |""".stripMargin)
    val site=new Designer(config);site.start();val client=HttpClient.newHttpClient()
    def post(path:String,body:com.fasterxml.jackson.databind.JsonNode)=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString)).build(),HttpResponse.BodyHandlers.ofString())
    try {
      val roots=post("/api/catalogs",Json.obj());assert(roots.statusCode()==200 && !roots.body().contains("not-exposed.token"))
      assert(Json.mapper.readTree(roots.body()).size()==2)
      val datasets=post("/api/datasets",Json.obj().put("uri","context:/"));assert(datasets.statusCode()==200)
      assert(Json.mapper.readTree(datasets.body()).path("datasets").get(0).path("uri").asText=="context:/weather")
      val items=post("/api/items",Json.obj().put("uri","context:/weather").put("path","/"));assert(items.statusCode()==200)
      assert(Json.mapper.readTree(items.body()).path("items").get(0).path("kind").asText=="folder")
      val nested=post("/api/items",Json.obj().put("uri","context:/weather").put("path","/nested"));assert(nested.statusCode()==200 && nested.body().contains("/nested/data.csv"))
      val yaml="name: Preview\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: 'context:/weather/nested/data.csv', sql: 'SELECT station FROM source LIMIT 1'}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: read}\n"
      val inferred=post("/api/infer",Json.obj().put("job_yaml",yaml).put("variables_yaml","{}").set[com.fasterxml.jackson.databind.JsonNode]("preview",Json.obj().put("max_rows",1).put("max_bytes",1024)))
      assert(inferred.statusCode()==200,inferred.body());val output=Json.mapper.readTree(inferred.body()).path("steps").path("read").path("outputs").path("result")
      assert(output.path("certainty").asText=="known",inferred.body())
      assert(output.path("schema_json").path("fields").size()==1)
      assert(output.path("sample").path("rows").size()==1,inferred.body())
      // Removing the Sink edge leaves a valid authoring draft: schemas must
      // survive fresh requests with no retained browser state or edit-session cache.
      val disconnected=yaml.replace("inputs: {result: read}","inputs: {}")
      val request=Json.obj().put("job_yaml",disconnected).put("variables_yaml","{}")
      for(_<-1 to 2){
        val response=post("/api/infer",request)
        assert(response.statusCode()==200,response.body())
        val draft=Json.mapper.readTree(response.body())
        assert(draft.path("steps").path("read").path("outputs").path("result").path("schema_json")==output.path("schema_json"))
        assert(draft.path("outputs").size()==0)
        val stream=post("/api/infer_stream",request)
        assert(stream.statusCode()==200,stream.body())
        val events=stream.body().linesIterator.filter(_.nonEmpty).map(Json.mapper.readTree(_)).toVector
        assert(!events.exists(_.path("event").asText=="error"),stream.body())
        val complete=events.find(_.path("event").asText=="complete").get.path("report")
        assert(complete.path("steps").path("read").path("outputs").path("result").path("schema_json")==output.path("schema_json"))
      }
      assert(post("/api/export",request).statusCode()==400,"Disconnected Sink must still fail executable export")
      for((path,view)<-Seq("geotrans-input.zip"->"bytes","geotrans-input.zip"->"items","geotrans-input.zip/labels"->"items","geotrans-input.zip/labels/points.txt"->"bytes")) {
        val zipYaml=s"name: Archive\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: 'context:/weather/$path', view: $view}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: read}\n"
        val result=post("/api/infer",Json.obj().put("job_yaml",zipYaml).put("variables_yaml","{}").set[com.fasterxml.jackson.databind.JsonNode]("preview",Json.obj().put("max_rows",1).put("max_bytes",1024)))
        assert(result.statusCode()==200,result.body())
        assert(Json.mapper.readTree(result.body()).path("steps").path("read").path("outputs").path("result").path("certainty").asText=="known",result.body())
        val exported=post("/api/export",Json.obj().put("job_yaml",zipYaml).put("variables_yaml","{}").put("include_designer",true))
        assert(exported.statusCode()==200 && exported.body().contains(path),exported.body())
      }
      for((path,view)<-Seq("geotrans-input.zip/labels"->"bytes","geotrans-input.zip"->"records")) {
        val bad=s"name: Invalid\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: 'context:/weather/$path', view: $view}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: read}\n"
        val result=post("/api/infer",Json.obj().put("job_yaml",bad).put("variables_yaml","{}"))
        assert(!Json.mapper.readTree(result.body()).path("steps").path("read").path("outputs").has("result"),result.body())
      }

    }finally {site.close();xstream.data.Data.deleteTree(dir)}
  }

  test("model conversation clarifies requirements and proposes a complete Job without running it") {
    val answer=new java.util.concurrent.atomic.AtomicReference[com.fasterxml.jackson.databind.JsonNode](
      Json.obj().put("reply","Which Item contains observations?").putNull("job_yaml").putNull("variables_yaml"))
    val observed=new java.util.concurrent.atomic.AtomicReference[com.fasterxml.jackson.databind.JsonNode]()
    val model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0)
    model.createContext("/v1/operators",x=>{
      val bytes="""[{"name":"item_source","version":"1.0","role":"source","type":"sdk","icon":{"path":"M3 5h18v14H3z"},"parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false}},{"name":"client_sink","version":"1.0","role":"sink","type":"sdk","icon":{"path":"M3 5h18v14H3z"},"parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false}}]""".getBytes(UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody.write(bytes);x.close()
    })
    model.createContext("/completions",x=>{
      observed.set(Json.mapper.readTree(x.getRequestBody.readAllBytes()))
      val response=Json.obj()
      response.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("content",answer.get.toString)
      val bytes=Json.mapper.writeValueAsBytes(response);x.sendResponseHeaders(200,bytes.length);x.getResponseBody.write(bytes);x.close()
    })
    model.start()
    val data=java.nio.file.Files.createTempDirectory("chat-catalog-")
    java.nio.file.Files.writeString(data.resolve("weather.csv"),"station,temperature\nA,12\n")
    val config=Json.obj().put("host","127.0.0.1").put("port",0)
    config.putObject("local_catalog").putObject("datasets").putObject("weather").put("provider","directory").putObject("source").put("root",data.toString)
    val origin=s"http://127.0.0.1:${model.getAddress.getPort}"
    config.putArray("remote_operator_library_urls").add(origin)
    config.putObject("chat").put("url",origin+"/completions").put("model","test-model")
    val site=new Designer(config);site.start()
    val client=HttpClient.newHttpClient()
    val request=Json.obj().put("job_yaml","name: Draft\nsteps: []\n").put("variables_yaml","{}").put("message","Analyze my weather observations and return the resulting stream")
    request.putArray("catalog_items").addObject().put("uri","context:/weather/observations").putArray("views").add("records")
    def send()=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}/api/chat")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString)).build(),HttpResponse.BodyHandlers.ofString())
    try {
      val clarification=send()
      assert(clarification.statusCode()==200,clarification.body())
      assert(Json.mapper.readTree(clarification.body()).path("job_yaml").isNull)
      val context=observed.get.path("messages").get(1).path("content").asText
      assert(context.contains("server_catalogs") && context.contains("context:/weather/weather.csv"),context)
      assert(context.contains("repository_urls") && context.contains("records"),context)
      assert(context.contains("designer_interface") && context.contains("Choose design target") && context.contains("Load example"),context)
      assert(context.contains("read-records") && context.contains("available_to_load"),context)

      val proposal="name: Weather analysis\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: $INPUT_URI}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: read}\n"
      answer.set(Json.obj().put("reply","Here is a complete Job proposal").put("job_yaml",proposal).put("variables_yaml","INPUT_URI: context:/weather/observations\nN: 123\n"))
      request.put("message","Use context:/weather/observations")
      val result=send()
      assert(result.statusCode()==200,result.body())
      assert(Documents.job(Json.mapper.readTree(result.body()).path("job_yaml").asText).path("steps").size()==2)
      assert(observed.get.path("messages").get(1).path("content").asText.contains("context:/weather/observations"))
      def stream()=client.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${site.address.getPort}/api/chat_stream")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString)).build(),HttpResponse.BodyHandlers.ofString())
      val streamed=stream();assert(streamed.statusCode()==200)
      assert(streamed.headers().firstValue("Content-Type").orElse("").contains("application/x-ndjson"))
      val events=streamed.body().linesIterator.filter(_.nonEmpty).map(Json.mapper.readTree).toVector
      assert(events.map(_.path("type").asText)==Vector("progress","progress","progress","progress","result"))
      assert(events.last.path("result").path("job_yaml").asText==proposal)
      answer.set(Json.obj().put("reply","Invalid operator").put("job_yaml",proposal.replace("item_source@1.0","unknown@1.0")).put("variables_yaml","{}"))
      assert(send().statusCode()==400)
      val rejected=stream().body().linesIterator.filter(_.nonEmpty).map(Json.mapper.readTree).toVector
      assert(rejected.last.path("type").asText=="error")
      assert(!rejected.exists(_.path("type").asText=="result"))
    } finally {site.close();model.stop(0);xstream.data.Data.deleteTree(data)}
  }
}
