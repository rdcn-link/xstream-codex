package xstream.engine

import xstream.execution._
import xstream.data._
import xstream.util.Json
import xstream.operator.inference.ResolvedOperator
import com.fasterxml.jackson.databind.JsonNode
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

class CheckpointSpec extends AnyFunSuite {
  private def fixture(f:(LocalEngine,Path)=>Unit):Unit={
    val dir=Files.createTempDirectory("checkpoint-test-");val plugins=new PluginEnvironment()
    val settings=Json.obj().put("execution_directory",dir.resolve("history").toString).put("operator_fusion","isolated").put("max_concurrent_tasks",8)
    val cp=settings.putObject("checkpoint_store").put("provider","file");cp.putObject("settings").put("directory",dir.resolve("checkpoints").toString)
    val host=new LocalEngine(settings,plugins)
    try f(host,dir)finally {host.close();plugins.close();Data.deleteTree(dir)}
  }
  private def task(id:String,input:Option[String]=None)=Task(id,input.map(i=>Map("source"->InputBinding(task=Some(i)))).getOrElse(Map.empty),"fixture@1.0",new java.util.LinkedHashMap[String,Object]())
  private val plan=JobPlan(Vector(task("source"),task("sort","source".some),task("analyze","sort".some)),Map("result"->("analyze","result")))
  private implicit class SomeString(s:String){def some:Option[String]=Some(s)}
  private def run(host:LocalEngine,p:JobPlan,policy:JsonNode,executor:TaskExecutor):String={
    val bytes=new java.io.ByteArrayOutputStream()
    val sink=new ResultSink {def accept(f:OperatorFrame):Unit=if(StreamProtocol.header(f).path("type").asText=="data")bytes.write(f.data);def onComplete():Unit=();def onDiscard(t:Throwable):Unit=bytes.reset();def close():Unit=()}
    val cancel=new StreamCancel(timeoutSeconds=15)
    try new LocalJobExecution(host,executor,(_,_)=>fail("no item input"),host.allocator).run(p,ExecutionPolicy.parse(policy,p),host.executionHistory.begin("test"),sink.accept,cancel,Some(sink))finally cancel.close()
    bytes.toString("UTF-8")
  }
  private def fixtureExecutor(host:LocalEngine,source:AtomicInteger,sort:AtomicInteger,analyze:AtomicInteger,failSource:Boolean=false)=new TaskExecutor(host,host.allocator){
    override def execute(t:Task,inputs:Map[String,FrameSource],emit:OperatorFrame=>Unit,cancel:StreamCancel,resolved:Option[ResolvedOperator],onEvent:Option[JsonNode=>Unit],onRuntime:Option[JsonNode=>Unit],resources:ExecutionResources,sinks:Option[SinkTransactions],attemptId:String,jobId:String):Unit={
      val n=(if(t.id=="source")source else if(t.id=="sort")sort else analyze).incrementAndGet()
      if(t.id=="source"){
        emit(StreamProtocol.start("result","bytes","text"));emit(StreamProtocol.frame("data","result","ordered-result".getBytes));
        if(failSource && n==1)throw new RuntimeException("source interrupted")
        emit(StreamProtocol.frame("end","result"))
      }else {
        var f=inputs("source").next()
        while(f.nonEmpty){emit(StreamProtocol.rename(f.get,"result"));f=if(StreamProtocol.header(f.get).path("type").asText=="end")None else inputs("source").next()}
        if(t.id=="analyze" && n==1)throw new RuntimeException("downstream failed")
      }
    }
  }
  test("downstream failure reuses committed sort output and skips all upstream execution") {fixture{(host,dir)=>
    val source=new AtomicInteger;val sort=new AtomicInteger;val analyze=new AtomicInteger
    val policy=Json.obj().put("max_retries",1);policy.putObject("checkpoints").putArray("sort").add("result")
    assert(run(host,plan,policy,fixtureExecutor(host,source,sort,analyze))=="ordered-result")
    assert(source.get==1 && sort.get==1 && analyze.get==2)
    val files=Files.list(dir.resolve("checkpoints"));try assert(files.iterator.asScala.map(_.getFileName.toString).toVector==Vector(".writer.lock"))finally files.close()
  }}
  test("streaming defaults rerun every step without checkpoint artifacts") {fixture{(host,dir)=>
    val source=new AtomicInteger;val sort=new AtomicInteger;val analyze=new AtomicInteger
    assert(run(host,plan,Json.obj().put("max_retries",1),fixtureExecutor(host,source,sort,analyze))=="ordered-result")
    assert(source.get==2 && sort.get==2 && analyze.get==2)
  }}
  test("incomplete outputs cannot become checkpoints and are recomputed") {fixture{(host,_)=>
    val source=new AtomicInteger;val sort=new AtomicInteger;val analyze=new AtomicInteger
    val policy=Json.obj().put("max_retries",2);policy.putObject("checkpoints").putArray("source").add("result")
    assert(run(host,plan,policy,fixtureExecutor(host,source,sort,analyze,failSource=true))=="ordered-result")
    assert(source.get==2 && analyze.get==2)
  }}
  test("multi-port checkpoints replay concurrently even when the consumer reads ports in reverse order") {fixture{(host,_)=>
    val builds=new AtomicInteger;val merges=new AtomicInteger
    val split=task("split")
    val merge=Task("merge",Map("a"->InputBinding(task=Some("split"),port="a"),"z"->InputBinding(task=Some("split"),port="z")),"fixture@1.0",new java.util.LinkedHashMap[String,Object]())
    val p=JobPlan(Vector(split,merge),Map("result"->("merge","result")))
    val executor=new TaskExecutor(host,host.allocator){
      override def execute(t:Task,inputs:Map[String,FrameSource],emit:OperatorFrame=>Unit,cancel:StreamCancel,resolved:Option[ResolvedOperator],onEvent:Option[JsonNode=>Unit],onRuntime:Option[JsonNode=>Unit],resources:ExecutionResources,sinks:Option[SinkTransactions],attemptId:String,jobId:String):Unit={
        if(t.id=="split"){
          builds.incrementAndGet()
          Seq("a","z").foreach { port=>
            emit(StreamProtocol.start(port,"bytes","text"))
            (1 to 10).foreach(_=>emit(StreamProtocol.frame("data",port,port.getBytes)))
            emit(StreamProtocol.frame("end",port))
          }
        }else {
          val n=merges.incrementAndGet();emit(StreamProtocol.start("result","bytes","text"))
          Seq("z","a").foreach { port=>
            var f=inputs(port).next()
            while(f.nonEmpty){val kind=StreamProtocol.header(f.get).path("type").asText;if(kind=="data")emit(StreamProtocol.rename(f.get,"result"));f=if(kind=="end")None else inputs(port).next()}
          }
          emit(StreamProtocol.frame("end","result"));if(n==1)throw new RuntimeException("retry merge")
        }
      }
    }
    val policy=Json.obj().put("max_retries",1);policy.putObject("checkpoints").putArray("split").add("a").add("z")
    assert(run(host,p,policy,executor)=="z"*10+"a"*10)
    assert(builds.get==1 && merges.get==2)
  }}
  test("terminal failure removes committed checkpoints and partial results") {fixture{(host,dir)=>
    val source=new AtomicInteger;val sort=new AtomicInteger;val analyze=new AtomicInteger
    val policy=Json.obj();policy.putObject("checkpoints").putArray("sort").add("result")
    intercept[RuntimeException](run(host,plan,policy,fixtureExecutor(host,source,sort,analyze)))
    assert(source.get==1 && sort.get==1 && analyze.get==1)
    val files=Files.list(dir.resolve("checkpoints"))
    try assert(files.iterator.asScala.map(_.getFileName.toString).toVector==Vector(".writer.lock"))finally files.close()
  }}
  test("checkpoint requests require an administrator-configured store before any step runs") {
    val dir=Files.createTempDirectory("checkpoint-no-store-");val plugins=new PluginEnvironment()
    val host=new LocalEngine(Json.obj().put("execution_directory",dir.resolve("history").toString),plugins)
    try {
      val source=new AtomicInteger;val sort=new AtomicInteger;val analyze=new AtomicInteger
      val policy=Json.obj();policy.putObject("checkpoints").putArray("sort").add("result")
      assert(intercept[IllegalArgumentException](run(host,plan,policy,fixtureExecutor(host,source,sort,analyze))).getMessage.contains("checkpoint_store"))
      assert(source.get==0 && sort.get==0 && analyze.get==0)
    }finally {host.close();plugins.close();Data.deleteTree(dir)}
  }
  test("policy rejects unknown steps, ports, duplicates and obsolete materialization settings") {
    for(raw<-Seq("""{"checkpoints":{"missing":["result"]}}""","""{"checkpoints":{"sort":["missing"]}}""","""{"checkpoints":{"sort":["result","result"]}}""","""{"checkpoints":{"sort":[]}}""","""{"checkpoints":{"sort":"result"}}""","""{"materialization":{}}"""))intercept[IllegalArgumentException](ExecutionPolicy.parse(Json.mapper.readTree(raw),plan))
    assert(ExecutionPolicy.parse(Json.obj(),plan).checkpoints.isEmpty)
  }
  test("mixed streaming and checkpointed outputs cannot create a bounded-stream barrier cycle") {
    val split=task("split")
    val merge=Task("merge",Map("a"->InputBinding(task=Some("split"),port="a"),"b"->InputBinding(task=Some("split"),port="b")),"fixture@1.0",new java.util.LinkedHashMap[String,Object]())
    val p=JobPlan(Vector(split,merge),Map("result"->("merge","result")))
    val policy=Json.obj();policy.putObject("checkpoints").putArray("split").add("a")
    assert(intercept[IllegalArgumentException](ExecutionPolicy.parse(policy,p)).getMessage.contains("rejoin"))
    policy.withObject("/checkpoints").withArray("split").add("b")
    assert(ExecutionPolicy.parse(policy,p).checkpoints.size==2)
  }
  test("recovery traversal keeps non-checkpointed input branches and side-effect sinks") {
    val join=Task("join",Map("a"->InputBinding(task=Some("sort")),"b"->InputBinding(task=Some("other"))),"join@1.0",new java.util.LinkedHashMap[String,Object]())
    val p=JobPlan(Vector(task("source"),task("sort",Some("source")),task("other"),join,task("sink",Some("join"))),Map.empty)
    assert(CheckpointRecovery.needed(p,Set("sort"->"result"),Set("sort"))==Set("sort","other","join","sink"))
  }
}
