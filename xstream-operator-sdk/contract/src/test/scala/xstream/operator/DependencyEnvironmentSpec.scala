package xstream.operator

import java.nio.file.{Files,Path}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._

class DependencyEnvironmentSpec extends AnyFunSuite {
  val mapper=DependencyEnvironments.mapper
  val image="sha256:"+"a"*64
  def request(root:Path)=DependencyRequest(root,image,image,mapper.createObjectNode())
  class Provider extends DependencyEnvironmentProvider {
    val id="test";val count=new AtomicInteger()
    def resolve(r:DependencyRequest,w:Path,c:()=>Unit)=mapper.readTree("""{"platform":"linux/amd64","files":[{"sha256":"abc","path":"lib/a.so"}]}""")
    def build(r:DependencyRequest,l:com.fasterxml.jackson.databind.JsonNode,w:Path,c:()=>Unit)={count.incrementAndGet();c();val a=w.resolve("runtime.tar");Files.writeString(a,"archive");image->a}
  }
  test("environment identities ignore mapping order and include base runtime and dependency content") {
    val a=mapper.readTree("""{"a":1,"b":2}""");val b=mapper.readTree("""{"b":2,"a":1}""")
    assert(DependencyEnvironments.fingerprint(image,a)==DependencyEnvironments.fingerprint(image,b))
    assert(DependencyEnvironments.fingerprint(image,a)!=DependencyEnvironments.fingerprint("sha256:"+"b"*64,a))
    assert(DependencyEnvironments.fingerprint(image,a)!=DependencyEnvironments.fingerprint(image,mapper.readTree("""{"a":2,"b":2}""")))
  }
  test("parallel acquisitions build once; leases prevent idle eviction") {
    implicit val ec:ExecutionContext=ExecutionContext.global
    val root=Files.createTempDirectory("dependency-cache-test-");val p=new Provider
    try {
      val cache=new DependencyEnvironmentCache(root,1)
      val leases=Await.result(Future.sequence((1 to 4).map(_=>Future(cache.acquire(p,request(root),()=>())))),20.seconds)
      assert(p.count.get()==1);assert(leases.map(_.value.fingerprint).distinct.size==1)
      cache.cleanup(System.currentTimeMillis()+100000);assert(Files.exists(leases.head.value.archive))
      leases.foreach(_.close());cache.cleanup(System.currentTimeMillis()+100000);assert(!Files.exists(leases.head.value.archive))
    }finally DependencyEnvironments.delete(root)
  }
  test("cancellation leaves no published environment and corruption is rejected") {
    val root=Files.createTempDirectory("dependency-cache-test-");val p=new Provider
    try {
      val cache=new DependencyEnvironmentCache(root)
      val lease=cache.acquire(p,request(root),()=>());val a=lease.value.archive;lease.close();Files.writeString(a,"tampered")
      intercept[IllegalArgumentException](cache.acquire(p,request(root),()=>()))
      intercept[java.util.concurrent.CancellationException](cache.acquire(p,request(root),()=>throw new java.util.concurrent.CancellationException()))
    }finally DependencyEnvironments.delete(root)
  }
}
