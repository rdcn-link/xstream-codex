package xstream.engine

import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.nio.channels.{ServerSocketChannel,SocketChannel}
import java.nio.file.{Files,Path}
import java.util.concurrent.TimeUnit
import xstream.execution.{SinkParticipant,SinkProtocol}
import scala.util.control.NonFatal

/** Private per-container control socket. No network, credentials or extra mounts are exposed. */
private[engine] final class SinkSession(val id:String,directory:Path,finish:(Boolean,Long)=>Unit,cleanup:()=>Unit) extends SinkParticipant {
  private val socket=directory.resolve(SinkProtocol.SocketName)
  private val server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)
  server.bind(UnixDomainSocketAddress.of(socket));server.configureBlocking(false)
  private var channel:SocketChannel=null
  private var started=false
  private var prepared=false
  private var closed=false
  def markStarted():Unit=synchronized {started=true}
  private def connection(deadline:Long):SocketChannel={
    if(channel==null){
      while(channel==null){
        if(System.nanoTime()>=deadline)throw new java.util.concurrent.TimeoutException("Sink did not establish its lifecycle channel: "+id)
        channel=server.accept();if(channel==null)Thread.sleep(5)
      }
      channel.configureBlocking(false)
      require(SinkProtocol.read(channel,deadline).path("state").asText=="ready","invalid Sink lifecycle handshake")
    };channel
  }
  def prepare():Unit=synchronized {
    require(started && !prepared,"invalid Sink preparation")
    connection(System.nanoTime()+TimeUnit.SECONDS.toNanos(30));prepared=true
  }
  private def decide(commit:Boolean,cause:Option[Throwable]):Unit=synchronized {
    val interrupted=Thread.interrupted()
    try {
      if(started){
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        val c=connection(deadline)
        val command=SinkProtocol.message(if(commit)"complete"else"discard")
        cause.foreach(t=>command.put("message",Option(t.getMessage).getOrElse("attempt failed").take(2048)))
        var sendError:Throwable=null
        try SinkProtocol.write(c,command,deadline)catch{case NonFatal(t)=>sendError=t}
        val response=try SinkProtocol.read(c,deadline)catch{case NonFatal(t)=>if(sendError!=null)t.addSuppressed(sendError);throw t}
        require(response.path("state").asText==(if(commit)"completed"else"discarded"),"Sink lifecycle callback failed: "+response.path("state").asText)
        finish(commit,deadline)
      }
    }finally if(interrupted)Thread.currentThread().interrupt()
  }
  def onComplete():Unit={require(prepared,"Sink has not finished staging");decide(true,None)}
  def onDiscard(cause:Throwable):Unit=decide(false,Some(cause))
  def close():Unit=synchronized {if(!closed){closed=true;try {if(channel!=null)channel.close();server.close()}finally {try cleanup()finally Files.deleteIfExists(socket)}}}
}
