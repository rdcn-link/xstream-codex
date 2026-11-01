package xstream.engine

import _root_.xstream.execution.{ExecutionStoreProvider,FrameSource,InferenceRequest,InferenceResult,MaterializationStore,MaterializationStoreDefinition,MaterializationStoreProvider,StreamCancel}

import _root_.xstream.operator.inference._

import _root_.xstream.data._

import java.nio.file.Path
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.memory.{BufferAllocator,RootAllocator}

/** Standalone engine host. Remote Item access is supplied as an adapter, never a server dependency. */
final class LocalEngine(settings:JsonNode,val plugins:PluginEnvironment,
  remote:(InputBinding,BufferAllocator,StreamCancel)=>FrameSource=(_,_,_)=>throw new IllegalArgumentException("No remote Item adapter configured"),
  suppliedTargets:Seq[_root_.xstream.execution.ExecutionTarget]=Seq.empty) extends EngineEnvironment with AutoCloseable {
  require(!settings.has("compute_pushdown_enabled"),"use execution_targets and placement policy instead of compute_pushdown_enabled")
  override val executionTargets:Seq[_root_.xstream.execution.ExecutionTarget]={
    import scala.jdk.CollectionConverters._
    val raw=settings.path("execution_targets")
    require(raw.isMissingNode || raw.isArray,"execution_targets must be a list")
    val configured=raw.elements.asScala.map { n=>
      _root_.xstream.util.Json.fields(n,Set("name","provider","settings"))
      plugins.byId(classOf[_root_.xstream.execution.ExecutionTargetProvider],n.path("provider").asText).create(n.path("name").asText,n.path("settings"))
    }.toVector
    val all=suppliedTargets++configured
    require(all.map(_.id).distinct.size==all.size,"duplicate execution target name")
    all
  }
  private val catalogHost=new ConfiguredDataCatalog(settings,plugins)
  val allocator=new RootAllocator(Long.MaxValue)
  val engine=new JobEngine(this,allocator)
  override val localOperatorBase:Option[Path]=Some(Path.of(settings.path("job_working_directory").asText(".")).toAbsolutePath.normalize)
  def debug:Boolean=false
  def sourceRuntimes:Map[String,SourceRuntime]=Map.empty
  def operatorFusionEnabled:Boolean=settings.path("operator_fusion_enabled").asBoolean(true)
  def maxConcurrentTasks:Int=settings.path("max_concurrent_tasks").asInt(2)
  val taskSlots=new java.util.concurrent.Semaphore(maxConcurrentTasks)
  def executionTimeoutSeconds:Long=settings.path("execution_timeout_seconds").asLong(1800)
  def localOperatorLibrary:Path=Option(settings.get("local_operator_library")).map(n=>Path.of(n.asText)).getOrElse(Path.of(settings.path("operator_cache").asText(".xstream-operators"),"library"))
  def operatorCache:Path=Path.of(settings.path("operator_cache").asText(".xstream-operators"))
  def operatorMaxBytes:Long=settings.path("operator_max_bytes").asLong(8L*1024*1024*1024)
  def remoteOperatorLibraryUrls:Seq[String]={import scala.jdk.CollectionConverters._;settings.path("remote_operator_library_urls").elements.asScala.map(_.asText).toVector}
  val inferenceWorkerPool=new InferenceWorkerPool(InferenceWorkerSettings())
  private val stores=scala.collection.mutable.Map.empty[MaterializationStoreDefinition,MaterializationStore]
  def materializationStore:Option[MaterializationStoreDefinition]=Option(settings.get("materialization_store")).map {n=>import scala.jdk.CollectionConverters._;MaterializationStoreDefinition(n.path("provider").asText,Data.mapper.convertValue(n.path("settings"),classOf[java.util.LinkedHashMap[String,Object]]).asScala.toMap)}
  def openMaterialization(d:MaterializationStoreDefinition):MaterializationStore=synchronized {stores.getOrElseUpdate(d,plugins.byId(classOf[MaterializationStoreProvider],d.provider).open(d.settings))}
  val executionHistory=new ExecutionHistory(plugins.byId(classOf[ExecutionStoreProvider],"file").open(Map("directory"->settings.path("execution_directory").asText(".xstream-executions")),false))
  def openItem(a:BufferAllocator,b:InputBinding,c:StreamCancel):FrameSource=if(b.uri.exists(_.startsWith("context:")))CatalogItemSource.open(engine.dataCatalog,plugins,a,b,c)else {
    val providers=plugins.services(classOf[_root_.xstream.execution.RemoteItemProvider]).filter(_.supports(b.uri.get))
    if(providers.nonEmpty){require(providers.size==1,"ambiguous remote Item provider");providers.head.open(settings,_root_.xstream.execution.RemoteItemRequest(b.uri.get,b.view,b.scan,b.selection),a,c)}
    else remote(b,a,c)
  }
  /** Trusted embedding API; remote inference endpoints never accept local paths. */
  def inferLocal(reference:String,request:InferenceRequest,cancel:StreamCancel):InferenceResult=
    OperatorInference.withDocker { docker=>
      val op=LocalOperatorLibrary.resolve(this,localOperatorBase.get,reference,docker,()=>cancel.active())
      OperatorInference.run(this,op,request,cancel)
    }
  def close():Unit={try {engine.close();executionTargets.foreach(_.close());inferenceWorkerPool.close();stores.values.foreach(_.close());executionHistory.close()}finally {try allocator.close()finally catalogHost.close()}}
}
