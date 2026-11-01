package xstream.engine

import xstream.execution._
import xstream.data._
import xstream.operator.inference.ResolvedOperator
import xstream.util.Json
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import com.fasterxml.jackson.databind.JsonNode

class LocalExecutionBoundarySpec extends AnyFunSuite {
  test("remote Item reads feed downstream steps on the local executor") {
    val dir=Files.createTempDirectory("local-execution-boundary-")
    val settings=Json.obj().put("execution_directory",dir.resolve("history").toString).put("operator_fusion_enabled",false)
    val plugins=new PluginEnvironment();var reads=0;var runs=0
    val host=new LocalEngine(settings,plugins,(binding,_,_)=>{
      reads+=1;assert(binding.uri.contains("dacp://remote:18861/weather/input"))
      val frames=Iterator(StreamProtocol.start("result","bytes","text"),StreamProtocol.frame("data","result","input".getBytes),StreamProtocol.frame("end","result"))
      new FrameSource {def next():Option[OperatorFrame]=if(frames.hasNext)Some(frames.next())else None;def close():Unit=()}
    })
    val executor=new TaskExecutor(host,host.allocator) {
      override def execute(t:Task,inputs:Map[String,FrameSource],emit:OperatorFrame=>Unit,c:StreamCancel,resolved:Option[ResolvedOperator],onEvent:Option[JsonNode=>Unit],onRuntime:Option[JsonNode=>Unit]):Unit={
        runs+=1;assert(t.id=="process");val source=inputs("source");var f=source.next()
        while(f.nonEmpty){emit(StreamProtocol.rename(f.get,"result"));f=if(StreamProtocol.header(f.get).path("type").asText=="end")None else source.next()}
      }
    }
    val engine=new JobEngine(host,host.allocator,suppliedExecutor=Some(executor));val cancel=new StreamCancel()
    val yaml="""name: Remote data, local execution
      |steps:
      |- id: read
      |  operator: read_item@1.0
      |  inputs: {}
      |  params: {uri: 'dacp://remote:18861/weather/input', view: bytes}
      |- id: process
      |  operator: passthrough@1.0
      |  inputs: {source: read}
      |outputs: {result: process}
      |""".stripMargin
    try {
      val bytes=new java.io.ByteArrayOutputStream()
      val id=engine.run(yaml,f=>if(StreamProtocol.header(f).path("type").asText=="data")bytes.write(f.data),cancel)
      assert(reads==1 && runs==1 && bytes.toString("UTF-8")=="input")
      assert(!host.executionHistory.list().exists(_.path("kind").asText=="remote_job"))
      assert(host.executionHistory.get(id).get.path("state").asText=="succeeded")
      intercept[IllegalArgumentException](ExecutionPolicy.parse(Json.obj().set[JsonNode]("placement",Json.obj().put("mode","auto")),Jobs.parse(yaml)))
    }finally {cancel.close();engine.close();host.close();plugins.close();Data.deleteTree(dir)}
  }
}
