package xstream.operator

import java.nio.file.{Files,Path,StandardOpenOption,StandardCopyOption}
import java.security.MessageDigest
import com.fasterxml.jackson.databind.{JsonNode,ObjectMapper}
import scala.jdk.CollectionConverters._

/** Dependency tools run in isolated builder containers, never in the engine JVM. */
case class DependencyRequest(source:Path,runtimeImage:String,builderImage:String,settings:JsonNode)
trait DependencyEnvironmentProvider extends xstream.data.Plugin {
  def id:String
  /** Resolve to a deterministic lock. Must include transitive artifacts and their digests. */
  def resolve(request:DependencyRequest,work:Path,check:()=>Unit):JsonNode
  /** Materialize exactly that lock; return a pinned image and its portable archive. */
  def build(request:DependencyRequest,lock:JsonNode,work:Path,check:()=>Unit):(String,Path)
}
case class LockedEnvironment(fingerprint:String,image:String,archive:Path,lock:JsonNode)
object DependencyEnvironments {
  val mapper=new ObjectMapper()
  def canonical(n:JsonNode):JsonNode={
    if(n.isObject){val out=mapper.createObjectNode();n.fieldNames.asScala.toVector.sorted.foreach(k=>out.set[JsonNode](k,canonical(n.get(k))));out}
    else if(n.isArray){val out=mapper.createArrayNode();n.elements.asScala.foreach(v=>out.add(canonical(v)));out}else n
  }
  def hash(bytes:Array[Byte]):String=MessageDigest.getInstance("SHA-256").digest(bytes).map(b=>f"${b&255}%02x").mkString
  def fingerprint(base:String,lock:JsonNode):String={
    require(base.matches("sha256:[a-f0-9]{64}"),"dependency runtime must be pinned")
    val n=mapper.createObjectNode().put("format",1).put("runtime_image",base);n.set[JsonNode]("lock",canonical(lock))
    hash(mapper.writeValueAsBytes(n))
  }
  private val monitors=new java.util.concurrent.ConcurrentHashMap[String,Object]()
  def locked[A](path:Path)(f: => A):A=monitors.computeIfAbsent(path.toAbsolutePath.normalize.toString,_=>new Object()).synchronized {
    Files.createDirectories(path.getParent)
    val channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE)
    val lock=channel.lock();try f finally {lock.release();channel.close()}
  }
  def delete(path:Path):Unit=if(Files.exists(path)){val s=Files.walk(path);try s.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))finally s.close()}
  def checksum(path:Path):String={val md=MessageDigest.getInstance("SHA-256");val in=Files.newInputStream(path);try {val b=new Array[Byte](65536);var n=in.read(b);while(n != -1){md.update(b,0,n);n=in.read(b)}}finally in.close();md.digest().map(b=>f"${b&255}%02x").mkString}
}
/** Atomic, cross-process cache. A lease protects an environment from idle eviction. */
final class DependencyEnvironmentCache(root:Path,idleMillis:Long=24L*3600*1000) {
  import DependencyEnvironments._
  require(idleMillis>0,"environment idle interval must be positive")
  Files.createDirectories(root)
  final class Lease(val value:LockedEnvironment,private val directory:Path,private val marker:Path) extends AutoCloseable {
    def close():Unit=locked(root.resolve(value.fingerprint+".lock")){Files.deleteIfExists(marker);if(Files.exists(directory))Files.setLastModifiedTime(directory,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));()}
  }
  def acquire(provider:DependencyEnvironmentProvider,request:DependencyRequest,check:()=>Unit):Lease={
    check();val work=Files.createTempDirectory(root,"resolve-")
    try {
      val lock=provider.resolve(request,work,check);require(lock.isObject,"dependency provider must return a lock mapping")
      val key=fingerprint(request.runtimeImage,lock);val directory=root.resolve(key)
      locked(root.resolve(key+".lock")){
        check();val metadata=directory.resolve("environment.json");val archive=directory.resolve("environment.tar")
        if(!Files.isRegularFile(metadata) || !Files.isRegularFile(archive)){
          val stage=Files.createTempDirectory(root,"build-")
          try {
            val (image,output)=provider.build(request,lock,work,check)
            require(image.matches("sha256:[a-f0-9]{64}") && Files.isRegularFile(output),"invalid built dependency environment")
            val target=stage.resolve("environment.tar");if(output!=target)Files.copy(output,target,StandardCopyOption.REPLACE_EXISTING)
            val n=mapper.createObjectNode().put("fingerprint",key).put("image",image).put("sha256",checksum(target));n.set[JsonNode]("lock",lock)
            Files.write(stage.resolve("environment.json"),mapper.writeValueAsBytes(n));check()
            delete(directory);Files.move(stage,directory,StandardCopyOption.ATOMIC_MOVE)
          }finally delete(stage)
        }
        val n=mapper.readTree(metadata.toFile)
        require(n.path("fingerprint").asText==key && n.path("lock")==lock && checksum(archive)==n.path("sha256").asText,"dependency cache corrupted")
        val lease=Files.createTempFile(directory,s"lease-${ProcessHandle.current().pid()}-",".ref")
        new Lease(LockedEnvironment(key,n.path("image").asText,archive,lock),directory,lease)
      }
    }finally delete(work)
  }
  /** Reclaim archives only, never remove Docker images used by another service. */
  def cleanup(now:Long=System.currentTimeMillis()):Unit={
    val stream=Files.list(root)
    try stream.iterator.asScala.filter(p=>Files.isDirectory(p) && p.getFileName.toString.matches("[a-f0-9]{64}")).foreach { p=>
      locked(root.resolve(p.getFileName.toString+".lock")){
        if(Files.exists(p)){
          val entries=Files.list(p);val refs=try entries.iterator.asScala.filter(_.toString.endsWith(".ref")).toVector finally entries.close()
          refs.foreach { f=>val pid=f.getFileName.toString.split("-")(1).toLong;if(!ProcessHandle.of(pid).isPresent)Files.deleteIfExists(f) }
          if(!refs.exists(Files.exists(_)) && now-Files.getLastModifiedTime(p).toMillis>idleMillis)delete(p)
        }
      }
    }finally stream.close()
  }
}
