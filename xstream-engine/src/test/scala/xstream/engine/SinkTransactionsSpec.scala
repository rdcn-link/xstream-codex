package xstream.engine

import xstream.execution._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ArrayBuffer

class SinkTransactionsSpec extends AnyFunSuite {
  private def participant(name:String,events:ArrayBuffer[String],failCommit:Boolean=false,failDiscard:Boolean=false)=new SinkParticipant {
    val id=name
    def onComplete():Unit={events+=s"commit:$id";if(failCommit)throw new RuntimeException("commit uncertain")}
    def onDiscard(cause:Throwable):Unit={events+=s"discard:$id";if(failDiscard)throw new RuntimeException("cleanup failed")}
    def close():Unit={events+=s"close:$id"}
  }
  test("discard and release run once even when cancellation and failure cleanup both request them") {
    val events=ArrayBuffer.empty[String];val tx=new SinkTransactions
    val abort=tx.register(participant("one",events));abort.close();tx.onDiscard(new RuntimeException("failed"));abort.close()
    assert(events.toVector==Vector("discard:one","close:one"));assert(!tx.commitStarted)
    intercept[IllegalArgumentException](tx.register(participant("late",events)))
  }
  test("whole Job success commits each participant once and never discards it afterwards") {
    val events=ArrayBuffer.empty[String];val tx=new SinkTransactions
    val abort=tx.register(participant("one",events));tx.onComplete();abort.close();tx.onDiscard(new RuntimeException("late close"))
    assert(events.toVector==Vector("commit:one","close:one"));assert(tx.states==Map("one"->"committed"))
  }
  test("a partial commit never rolls back committed or uncertain results and discards unstarted sinks") {
    val events=ArrayBuffer.empty[String];val tx=new SinkTransactions
    tx.register(participant("a",events));tx.register(participant("b",events,failCommit=true));tx.register(participant("c",events))
    intercept[SinkCommitException](tx.onComplete())
    assert(tx.commitStarted);assert(tx.states==Map("a"->"committed","b"->"commit_failed","c"->"discarded"))
    assert(events.toVector==Vector("commit:a","close:a","commit:b","close:b","discard:c","close:c"))
  }
  test("cleanup failure is retained and all other sinks are still cleaned") {
    val events=ArrayBuffer.empty[String];val tx=new SinkTransactions
    tx.register(participant("a",events));val abort=tx.register(participant("b",events,failDiscard=true))
    intercept[SinkDiscardException](abort.close())
    intercept[SinkDiscardException](tx.onDiscard(new RuntimeException("failed")))
    assert(events.count(_=="discard:b")==1);assert(events.contains("discard:a"))
  }
}
