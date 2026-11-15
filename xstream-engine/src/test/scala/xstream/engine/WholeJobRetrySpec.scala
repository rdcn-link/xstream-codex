package xstream.engine

import xstream.execution._
import xstream.data._
import xstream.util.Json
import xstream.operator.inference.ResolvedOperator
import com.fasterxml.jackson.databind.JsonNode
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer

class WholeJobRetrySpec extends AnyFunSuite {
  private def fixture(f:(LocalEngine,java.nio.file.Path)=>Unit):Unit={
    val dir=Files.createTempDirectory("whole-job-retry-");val plugins=new PluginEnvironment()
    val host=new LocalEngine(Json.obj().put("execution_directory",dir.resolve("history").toString).put("operator_fusion","isolated").put("max_concurrent_tasks",4),plugins)
    try f(host,dir)finally {host.close();plugins.close();Data.deleteTree(dir)}
  }
  test("downstream failure discards every sink and reruns all steps before committing only the successful attempt") {fixture{(host,_)=>
    val events=ArrayBuffer.empty[String];val sourceRuns=new java.util.concurrent.atomic.AtomicInteger();val processingRuns=new java.util.concurrent.atomic.AtomicInteger()
    val staged=new java.io.ByteArrayOutputStream();var result=Array.emptyByteArray;var discards=0;var commits=0
    val receiver=new ResultSink {
      def accept(f:OperatorFrame):Unit=if(StreamProtocol.header(f).path("type").asText=="data")staged.write(f.data)
      def onDiscard(t:Throwable):Unit={discards+=1;staged.reset()}
      def onComplete():Unit={commits+=1;result=staged.toByteArray}
      def close():Unit=()
    }
    val executor=new TaskExecutor(host,host.allocator){
      override def execute(t:Task,inputs:Map[String,FrameSource],emit:OperatorFrame=>Unit,c:StreamCancel,resolved:Option[ResolvedOperator],onEvent:Option[JsonNode=>Unit],onRuntime:Option[JsonNode=>Unit],resources:ExecutionResources,sinks:Option[SinkTransactions],attemptId:String,jobId:String):Unit={
        if(t.id=="source"){
          val n=sourceRuns.incrementAndGet();emit(StreamProtocol.start("result","bytes","text"));emit(StreamProtocol.frame("data","result",s"attempt-$n".getBytes));emit(StreamProtocol.frame("end","result"))
        }else{
          val n=processingRuns.incrementAndGet()
          val abort=sinks.get.register(new SinkParticipant{val id="external";def onComplete():Unit=events.synchronized{events+=s"commit:$n"};def onDiscard(e:Throwable):Unit=events.synchronized{events+=s"discard:$n"};def close():Unit=()})
          c.add(abort)
          var f=inputs("source").next();while(f.nonEmpty){emit(StreamProtocol.rename(f.get,"result"));f=if(StreamProtocol.header(f.get).path("type").asText=="end")None else inputs("source").next()}
          if(n==1)throw new RuntimeException("transient downstream failure")
        }
      }
    }
    val p=JobPlan(Vector(Task("source",Map.empty,"test_source@1.0",new java.util.LinkedHashMap[String,Object]()),Task("process",Map("source"->InputBinding(task=Some("source"))),"test_process@1.0",new java.util.LinkedHashMap[String,Object]())),Map("result"->("process","result")))
    val record=host.executionHistory.begin("test");val cancel=new StreamCancel()
    try new LocalJobExecution(host,executor,(_,_)=>fail("no item input"),host.allocator).run(p,ExecutionPolicy.parse(Json.obj().put("max_retries",1),p),record,f=>if(StreamProtocol.header(f).path("type").asText!="attempt_failed")receiver.accept(f),cancel,Some(receiver))finally cancel.close()
    assert(sourceRuns.get==2 && processingRuns.get==2);assert(discards==1 && commits==1)
    assert(new String(result,"UTF-8")=="attempt-2");assert(events.toVector==Vector("discard:1","commit:2"))
  }}
  test("client commit failure never starts another Job attempt") {fixture{(host,_)=>
    var reads=0;var commits=0
    val source: (InputBinding,StreamCancel)=>FrameSource=(_,_)=>{reads+=1;val it=Iterator(StreamProtocol.start("result","bytes","text"),StreamProtocol.frame("data","result",Array[Byte](1)),StreamProtocol.frame("end","result"));new FrameSource{def next():Option[OperatorFrame]=if(it.hasNext)Some(it.next())else None;def close():Unit=()}}
    val params=new java.util.LinkedHashMap[String,Object]();params.put("uri","context:/files/input");params.put("view","bytes")
    val plan=JobPlan(Vector(Task("read",Map.empty,Jobs.ItemSource,params)),Map("result"->("read","result")))
    val result=new ResultSink{def accept(f:OperatorFrame):Unit=();def onDiscard(t:Throwable):Unit=fail("uncertain client commit must not be discarded");def onComplete():Unit={commits+=1;throw new RuntimeException("commit failed")};def close():Unit=()}
    val cancel=new StreamCancel()
    try intercept[SinkCommitException](new LocalJobExecution(host,new TaskExecutor(host,host.allocator),source,host.allocator).run(plan,ExecutionPolicy.parse(Json.obj().put("max_retries",3),plan),host.executionHistory.begin("test"),result.accept,cancel,Some(result)))finally cancel.close()
    assert(reads==1 && commits==1)
  }}
}
