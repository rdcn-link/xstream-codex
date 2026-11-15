package xstream.execution


import java.util.concurrent.{TimeUnit,CancellationException}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.vector.types.pojo.Schema

case class PortContract(representations: Set[String], formats: Set[String], schema: Option[Schema],cardinality:Option[String]=None) {
  def accept(h: JsonNode): Unit = {
    val repr=h.path("representation").asText
    cardinality.foreach(c=>require(repr=="items" && h.path("structure").path("cardinality").asText==c,"Item cardinality mismatch"))
    require(repr!="variable" && (representations(repr) || representations("variable")),"unsupported port representation")
    require(formats.isEmpty || formats(h.path("format").asText),"unsupported port format")
    if(repr=="records") {
      val actual=org.apache.arrow.vector.types.pojo.Schema.deserializeMessage(java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder.decode(h.path("schema").asText)))
      schema.foreach(s=>require(_root_.xstream.data.ArrowStreamMetadata.logical(s)==_root_.xstream.data.ArrowStreamMetadata.logical(actual),"operator port schema mismatch"))
    } else if(repr!="bytes")require(h.path("structure").isObject && h.path("structure").path("view").asText==repr,"scientific stream needs a structure descriptor")
  }
}
object OperatorPorts {
  def representations(plugins:_root_.xstream.data.PluginEnvironment):Set[String]=Set("bytes","records") ++ _root_.xstream.data.DataViewSupports.all(plugins).map(_.kind.name)
  def read(n: JsonNode, availableViews:Set[String]=Set.empty): Map[String,PortContract] = {
    require(n.isObject,"operator must declare a port mapping")
    n.fields.asScala.map { e =>
      require(e.getKey.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"),"invalid operator port")
      val v=e.getValue
      require(v.isObject && v.fieldNames.asScala.toSet.subsetOf(Set("representations","formats","schema","cardinality")),"invalid port settings")
      require(v.path("representations").isArray && v.path("formats").isArray,"port representations/formats must be lists")
      val repr=v.path("representations").elements.asScala.map(_.asText).toSet
      require(v.path("representations").size()==1 && v.path("representations").get(0).isTextual && repr.size==1 && repr.forall(x=>x.matches("[a-z][a-z0-9_-]*") && x!="auto"),"input port must declare one View or variable")
      val cardinality=Option(v.get("cardinality")).map { n=>
        require(n.isTextual && Set("one","many")(n.asText) && repr==Set("items"),"cardinality requires an items-only port and one or many")
        n.asText
      }
      e.getKey -> PortContract(if(repr==Set("variable") && availableViews.nonEmpty)availableViews else repr,v.path("formats").elements.asScala.map(_.asText).toSet,
        Option(v.get("schema")).map(s=>Schema.fromJSON(s.toString)),cardinality)
    }.toMap
  }
}
final class StreamCancel(check: () => Boolean = () => false, timeoutSeconds: Long = 1800) extends AutoCloseable {
  private val failure=new AtomicReference[Throwable]()
  private val resources=new java.util.concurrent.ConcurrentLinkedDeque[AutoCloseable]()
  @volatile private var deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds)
  private var suspendedNanos=0L
  @volatile private var suspended=false
  /** Suspend only the time budget; cancellation and parent checks remain active. */
  def pauseTimeout():Unit=synchronized {checkState();if(!suspended){suspendedNanos=math.max(0L,deadline-System.nanoTime());suspended=true}}
  def resumeTimeout():Unit=synchronized {if(suspended){deadline=System.nanoTime()+suspendedNanos;suspended=false};checkState()}
  /** Begin a new bounded phase using the same cancellation/resource scope. */
  def restartTimeout(seconds:Long):Unit=synchronized {checkState();require(seconds>0);deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);suspended=false}
  def remainingSeconds:Long=synchronized {math.max(1L,((if(suspended)suspendedNanos else deadline-System.nanoTime())+TimeUnit.SECONDS.toNanos(1)-1)/TimeUnit.SECONDS.toNanos(1))}
  /** Inspect a parent scope without treating interruption of a child worker as parent cancellation. */
  def checkState():Unit={
    Option(failure.get()).foreach(throw _)
    if(check())fail(new CancellationException("stream cancelled"))
    if(!suspended && System.nanoTime()>deadline)fail(new java.util.concurrent.TimeoutException("job timed out"))
    Option(failure.get()).foreach(throw _)
  }
  def active(): Unit = {
    Option(failure.get()).foreach(throw _)
    if(Thread.currentThread().isInterrupted)fail(new CancellationException("stream cancelled"))
    checkState()
  }
  def add[T <: AutoCloseable](r: T): T = {resources.push(r);if(failure.get()!=null) try r.close() catch{case _:Throwable=>()};r}
  def cause(t: Throwable): Throwable = Option(failure.get()).getOrElse(t)
  def fail(t: Throwable): Unit = if(failure.compareAndSet(null,t)) closeResources()
  private def closeResources(): Unit = resources.iterator.asScala.foreach(r=>try r.close() catch{case _:Throwable=>()})
  def close(): Unit = {fail(new CancellationException("stream closed"));closeResources()}
}
