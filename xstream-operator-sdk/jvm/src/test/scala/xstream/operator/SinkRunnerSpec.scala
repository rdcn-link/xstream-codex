package xstream.operator

import xstream.execution._
import xstream.data.Data
import java.nio.file.{Files,Path,StandardCopyOption}
import java.nio.channels.{ServerSocketChannel,SocketChannel}
import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.util.concurrent.TimeUnit
import org.scalatest.funsuite.AnyFunSuite

object SinkLifecycleFixture extends SinkOperator {
  def inferOutputs(inputs:Map[String,StreamDescriptor],params:com.fasterxml.jackson.databind.JsonNode):InferenceResult=InferenceResult(Map.empty)
  def previewOutputs(inputs:Map[String,StreamDescriptor],params:com.fasterxml.jackson.databind.JsonNode,limits:PreviewLimits):Map[String,OutputSample]=Map.empty
  def process(c:OperatorContext):Unit={
    require(c.attemptId=="attempt-test" && c.jobId=="job-test")
    Files.writeString(Path.of(c.params.path("stage").asText),"new result")
    if(c.params.path("fail_process").asBoolean)throw new RuntimeException("stage failure")
  }
  def onComplete(c:OperatorContext):Unit={
    if(c.params.path("fail_commit").asBoolean)throw new RuntimeException("uncertain commit")
    Files.move(Path.of(c.params.path("stage").asText),Path.of(c.params.path("target").asText),StandardCopyOption.REPLACE_EXISTING)
  }
  def onDiscard(c:OperatorContext,cause:Throwable):Unit=Files.deleteIfExists(Path.of(c.params.path("stage").asText))
}

class SinkRunnerSpec extends AnyFunSuite {
  private def scenario(decision:String,failProcess:Boolean=false,failCommit:Boolean=false):Unit={
    val dir=Files.createTempDirectory("sink-sdk-");val socket=dir.resolve(SinkProtocol.SocketName)
    val server=ServerSocketChannel.open(StandardProtocolFamily.UNIX);server.bind(UnixDomainSocketAddress.of(socket));server.configureBlocking(false)
    Files.writeString(dir.resolve("manifest.json"),"[]")
    Files.writeString(dir.resolve("target"),"previous result")
    val params=Data.mapper.createObjectNode().put("stage",dir.resolve("stage").toString).put("target",dir.resolve("target").toString).put("fail_process",failProcess).put("fail_commit",failCommit)
    Files.writeString(dir.resolve("params.json"),params.toString)
    val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"--add-opens=java.base/java.nio=ALL-UNNAMED","-cp",System.getProperty("java.class.path"),"xstream.operator.SinkLifecycleFixture","operator",dir.toString,dir.resolve("params.json").toString)
    builder.environment().put("XSTREAM_SINK_SOCKET",socket.toString);builder.environment().put("XSTREAM_ATTEMPT_ID","attempt-test");builder.environment().put("XSTREAM_JOB_ID","job-test")
    builder.redirectError(dir.resolve("errors.log").toFile)
    val process=builder.start();var connection:SocketChannel=null
    try {
      val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
      while(connection==null && process.isAlive && System.nanoTime()<deadline){connection=server.accept();if(connection==null)Thread.sleep(5)}
      assert(connection!=null,Files.readString(dir.resolve("errors.log")))
      connection.configureBlocking(false)
      assert(SinkProtocol.read(connection,deadline).path("state").asText=="ready")
      if(!failProcess){
        val prepared=OperatorFrames.read(process.getInputStream).get
        assert(StreamProtocol.header(prepared).path("type").asText=="sink_prepared")
        assert(Files.readString(dir.resolve("target"))=="previous result")
        assert(Files.exists(dir.resolve("stage")))
        SinkProtocol.write(connection,SinkProtocol.message(decision),deadline)
      }
      val expected=if(failCommit)"commit_failed"else if(failProcess || decision=="discard")"discarded"else"completed"
      assert(SinkProtocol.read(connection,deadline).path("state").asText==expected)
      assert(process.waitFor(5,TimeUnit.SECONDS),"Sink process did not stop")
      if(expected=="completed")assert(Files.readString(dir.resolve("target"))=="new result")
      else assert(Files.readString(dir.resolve("target"))=="previous result")
      assert(Files.exists(dir.resolve("stage"))==failCommit)
    }finally {process.destroyForcibly();if(connection!=null)connection.close();server.close();Data.deleteTree(dir)}
  }
  test("staged SDK Sink publishes only after the Job complete decision"){scenario("complete")}
  test("discard decision cleans staging and leaves previous published result unchanged"){scenario("discard")}
  test("processing failure calls discard before the runtime exits"){scenario("discard",failProcess=true)}
  test("commit failure does not discard uncertain staged state"){scenario("complete",failCommit=true)}
}
