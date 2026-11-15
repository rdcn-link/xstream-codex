package xstream.execution

import java.nio.file.{Files,Path,StandardCopyOption,StandardOpenOption}
import java.nio.channels.FileChannel
import java.nio.ByteBuffer

/** Atomically publishes one bytes result port. Intermediate Job outputs are never retained. */
final class BytesFileSink(destination:Path,port:String="result") extends ResultSink {
  val path:Path=destination.toAbsolutePath.normalize
  require(port.nonEmpty,"result port must not be empty")
  require(Files.isDirectory(path.getParent),"result directory must already exist")
  private var temporary:Path=null
  private var channel:FileChannel=null
  private var started=false
  private var ended=false
  private var committed=false
  private var closed=false
  def accept(frame:OperatorFrame):Unit=synchronized {
    require(!closed && !committed,"result sink has ended")
    val h=StreamProtocol.header(frame)
    if(h.path("port").asText==port)h.path("type").asText match {
      case "start"=>
        require(!started && h.path("representation").asText=="bytes","expected one bytes output: "+port)
        temporary=Files.createTempFile(path.getParent,".xstream-result-",".tmp")
        channel=FileChannel.open(temporary,StandardOpenOption.WRITE);started=true
      case "data"=>
        require(started && !ended,"bytes data outside an active result stream")
        val buffer=ByteBuffer.wrap(frame.data);while(buffer.hasRemaining)channel.write(buffer)
      case "end"=>require(started && !ended,"invalid result end");ended=true
      case _=>throw new IllegalArgumentException("unexpected bytes result event")
    }
  }
  def onComplete():Unit=synchronized {
    require(!closed && !committed && started && ended,"bytes result is incomplete")
    channel.force(true);channel.close();channel=null
    // No non-atomic fallback: a failed publication must preserve the old destination.
    Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    temporary=null;committed=true
  }
  def onDiscard(cause:Throwable):Unit=synchronized {
    if(!committed){
      try {if(channel!=null)channel.close()}finally {channel=null;if(temporary!=null){Files.deleteIfExists(temporary);temporary=null}}
      started=false;ended=false
    }
  }
  def close():Unit=synchronized {if(!closed){try onDiscard(new java.util.concurrent.CancellationException("result receiver closed"))finally closed=true}}
}
