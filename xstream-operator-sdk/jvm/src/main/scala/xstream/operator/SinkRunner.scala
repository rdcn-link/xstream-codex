package xstream.operator

import java.nio.channels.SocketChannel
import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import xstream.execution.{OperatorFrames,SinkProtocol,StreamProtocol}
import scala.util.control.NonFatal

/** Keeps the isolated process and its temporary workspace alive until the Job decision. */
object SinkRunner {
  def run(sink:SinkOperator,args:Array[String]):Unit={
    require(args.length==3 && args(0)=="operator","expected operator INPUT PARAMS")
    val socket=sys.env.getOrElse("XSTREAM_SINK_SOCKET",throw new IllegalStateException("Sink requires a Job lifecycle coordinator"))
    val channel=SocketChannel.open(StandardProtocolFamily.UNIX)
    channel.connect(UnixDomainSocketAddress.of(socket));channel.configureBlocking(false)
    val context=new OperatorContext(Path.of(args(1)),Path.of(args(2)),System.out)
    val decision=new AtomicReference[String]()
    val controlError=new AtomicReference[Throwable]()
    val processing=Thread.currentThread()
    var committed=false
    var discardCalled=false
    val listener=new Thread(()=>try {
      val message=SinkProtocol.read(channel,Long.MaxValue)
      val state=message.path("state").asText
      require(Set("complete","discard")(state),"invalid Sink decision")
      decision.set(state)
      if(state=="discard")processing.interrupt()
    }catch{case NonFatal(t)=>controlError.set(t);processing.interrupt()},"xstream-sink-decision")
    listener.setDaemon(true)
    def ack(state:String):Unit=SinkProtocol.write(channel,SinkProtocol.message(state),System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10))
    try {
      SinkProtocol.write(channel,SinkProtocol.message("ready"),System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10))
      listener.start()
      Console.withOut(System.err){sink.process(context)}
      context.finish()
      OperatorFrames.write(System.out,StreamProtocol.frame("sink_prepared"))
      listener.join()
      Option(controlError.get()).foreach(throw _)
      if(decision.get()!="complete")throw new java.util.concurrent.CancellationException("Sink attempt discarded")
      // Failure after this point is an uncertain commit, never an automatic retry.
      committed=true
      Console.withOut(System.err){sink.onComplete(context)}
      ack("completed")
    }catch{case NonFatal(t)=>
      Thread.interrupted()
      if(!committed){
        discardCalled=true
        try {Console.withOut(System.err){sink.onDiscard(context,t)};ack("discarded")}
        catch{case NonFatal(cleanup)=>t.addSuppressed(cleanup);try ack("discard_failed")catch{case NonFatal(_)=>()}}
      }else try ack("commit_failed")catch{case NonFatal(_)=>()}
      throw t
    }finally {
      // InterruptedException is not NonFatal in Scala; cancellation still needs cleanup.
      Thread.interrupted()
      if(!committed && !discardCalled)try {Console.withOut(System.err){sink.onDiscard(context,new java.util.concurrent.CancellationException("Sink interrupted"))};ack("discarded")}catch{case NonFatal(_)=>try ack("discard_failed")catch{case NonFatal(_)=>()}}
      channel.close();listener.interrupt();listener.join(1000);context.close()
    }
  }
}
