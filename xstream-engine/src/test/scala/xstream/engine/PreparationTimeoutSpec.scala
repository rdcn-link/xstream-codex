package xstream.engine

import org.scalatest.funsuite.AnyFunSuite
import xstream.execution.StreamCancel
import java.util.concurrent.{CancellationException,TimeoutException}

class PreparationTimeoutSpec extends AnyFunSuite {
  test("preparation can exceed the execution budget while parent cancellation remains active") {
    val cancel=new StreamCancel(timeoutSeconds=1)
    try {
      cancel.pauseTimeout()
      Thread.sleep(1100)
      cancel.active()
      assert(cancel.remainingSeconds==1)
      cancel.resumeTimeout()
      cancel.active()
      cancel.fail(new CancellationException("cancel during preparation"))
      intercept[CancellationException](cancel.active())
    }finally cancel.close()
  }
  test("a new execution phase has its own deadline and expires normally") {
    val cancel=new StreamCancel(timeoutSeconds=30)
    try {
      cancel.restartTimeout(1)
      Thread.sleep(1100)
      intercept[TimeoutException](cancel.active())
      intercept[TimeoutException](cancel.restartTimeout(30))
    }finally cancel.close()
  }
  test("suspending timeout does not suspend parent revocation") {
    var revoked=false
    val cancel=new StreamCancel(()=>revoked,1)
    try {
      cancel.pauseTimeout();revoked=true
      intercept[CancellationException](cancel.active())
    }finally cancel.close()
  }
}
