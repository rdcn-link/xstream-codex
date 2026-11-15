package xstream.execution

import scala.collection.mutable
import scala.util.control.NonFatal

/** One attempt's staged external effect. Callbacks must be bounded and idempotent. */
trait SinkParticipant extends AutoCloseable {
  def id:String
  def commitOrder:Int=0
  def onComplete():Unit
  def onDiscard(cause:Throwable):Unit
}
final class SinkCommitException(message:String,cause:Throwable) extends RuntimeException(message,cause)
final class SinkDiscardException(message:String,cause:Throwable) extends RuntimeException(message,cause)

/** Job-wide commit boundary; never retries a partially committed or unclean attempt. */
final class SinkTransactions {
  private val entries=mutable.LinkedHashMap.empty[String,Entry]
  private var phase="staging"
  def commitStarted:Boolean=synchronized {phase=="committing" || phase=="committed" || phase=="commit_failed"}
  def states:Map[String,String]=synchronized {entries.iterator.map{case(id,e)=>id->e.state}.toMap}
  def register(sink:SinkParticipant):AutoCloseable=synchronized {
    require(phase=="staging","Sink registration after attempt ended")
    require(!entries.contains(sink.id),"duplicate Sink participant: "+sink.id)
    val e=new Entry(sink);entries(sink.id)=e
    new AutoCloseable {def close():Unit=e.discard(new java.util.concurrent.CancellationException("attempt cancelled"))}
  }
  private final class Entry(val sink:SinkParticipant) {
    @volatile var state="staged"
    private var error:Throwable=null
    private def release():Unit=try sink.close()catch{case NonFatal(t)=>if(error==null)error=t else error.addSuppressed(t)}
    def complete():Unit=synchronized {
      require(state=="staged","Sink is not available for commit: "+sink.id)
      state="committing"
      try {sink.onComplete();state="committed"}
      catch{case NonFatal(t)=>error=t;state="commit_failed"}
      finally release()
      if(error!=null)throw new SinkCommitException("Sink commit failed; outcome may be uncertain: "+sink.id,error)
    }
    def discard(cause:Throwable):Unit=synchronized {
      if(state=="staged"){
        state="discarding"
        try {sink.onDiscard(cause);state="discarded"}
        catch{case NonFatal(t)=>error=t;state="discard_failed"}
        finally release()
      }
      if(state=="discard_failed" || (state=="discarded" && error!=null))throw new SinkDiscardException("Sink cleanup failed; attempt cannot be retried: "+sink.id,error)
    }
  }
  def onComplete(check:()=>Unit=()=>()):Unit={
    val participants=synchronized {require(phase=="staging","attempt already ended");phase="committing";entries.values.toVector.sortBy(e=>(e.sink.commitOrder,e.sink.id))}
    try {participants.foreach { e=>check();e.complete() };synchronized {phase="committed"}}
    catch{case NonFatal(t)=>
      synchronized {phase="commit_failed"}
      // Previously committed and uncertain participants must never be rolled back as temporary data.
      participants.foreach(e=>try e.discard(t)catch{case NonFatal(cleanup)=>t.addSuppressed(cleanup)})
      throw new SinkCommitException("Job Sink commit failed; automatic retry is forbidden",t)
    }
  }
  def onDiscard(cause:Throwable):Unit={
    val participants=synchronized {if(phase=="staging")phase="discarding";entries.values.toVector.reverse}
    var failure:Throwable=null
    participants.foreach(e=>try e.discard(cause)catch{case NonFatal(t)=>if(failure==null)failure=t else failure.addSuppressed(t)})
    synchronized {if(phase=="discarding")phase=if(failure==null)"discarded"else"discard_failed"}
    if(failure!=null)throw new SinkDiscardException("Job Sink cleanup failed; automatic retry is forbidden",failure)
  }
}
