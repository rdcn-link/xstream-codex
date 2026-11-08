package xstream.designer
import org.scalatest.funsuite.AnyFunSuite
import xstream.util.Json
class ChatContextCacheSpec extends AnyFunSuite {
 test("catalog snapshots are reused, isolated by Target, expire and do not leak caller mutations"){
  var now=0L;var loads=0
  val cache=new ChatContextCache(300,()=>now)
  def load={loads+=1;Json.obj().put("value",loads)}
  val(first,hit)=cache.get("local")(load);assert(!hit);first.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("value",99)
  val(second,reused)=cache.get("local")(load);assert(reused&&loads==1&&second.path("value").asInt==1)
  assert(!cache.get("remote")(load)._2);assert(loads==2)
  now=java.util.concurrent.TimeUnit.SECONDS.toNanos(300);assert(!cache.get("local")(load)._2);assert(loads==3)
 }
 test("failed loads are not cached"){
  val cache=new ChatContextCache(300)
  intercept[IllegalStateException](cache.get("local")(throw new IllegalStateException("unavailable")))
  assert(!cache.get("local")(Json.obj())._2)
 }
}
