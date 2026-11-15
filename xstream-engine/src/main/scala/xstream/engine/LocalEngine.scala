package xstream.engine

import _root_.xstream.execution.{ExecutionStoreProvider,FrameSource,InferenceRequest,InferenceResult,StreamCancel}

import _root_.xstream.operator.inference._

import _root_.xstream.data._

import java.nio.file.Path
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.memory.{BufferAllocator,RootAllocator}

/** Standalone engine host. Remote Item access is supplied as an adapter, never a server dependency. */
final class LocalEngine(settings:JsonNode,val plugins:PluginEnvironment,
  remote:(InputBinding,BufferAllocator,StreamCancel)=>FrameSource=(_,_,_)=>throw new IllegalArgumentException("No remote Item adapter configured")) extends EngineEnvironment with AutoCloseable {
  require(!settings.has("execution_targets") && !settings.has("compute_pushdown_enabled"),"XStream executes Jobs locally; remote Job submission belongs to DACP")
  val operatorFusion:String=FusionPolicy.parse(Option(settings.get("operator_fusion")))
  private val catalogHost=new ConfiguredDataCatalog(settings,plugins)
  val allocator=new RootAllocator(Long.MaxValue)
  override val executionResourceLimits=ExecutionResourceLimits.parse(Option(settings.get("execution_resources")).getOrElse(_root_.xstream.util.Json.obj()))
  val engine=new JobEngine(this,allocator)
  require(!settings.has("materialization_store"),"unsupported engine setting: materialization_store")
  override val checkpointStore:Option[_root_.xstream.execution.CheckpointStore]=Option(settings.get("checkpoint_store")).map { d=>
    _root_.xstream.util.Json.fields(d,Set("provider","settings"))
    require(d.path("settings").isObject,"checkpoint_store.settings must be an object")
    import scala.jdk.CollectionConverters._
    val config=Data.mapper.convertValue(d.path("settings"),classOf[java.util.LinkedHashMap[String,Object]]).asScala.toMap
    plugins.byId(classOf[_root_.xstream.execution.CheckpointStoreProvider],d.path("provider").asText).open(config)
  }
  override val localOperatorBase:Option[Path]=Some(Path.of(settings.path("job_working_directory").asText(".")).toAbsolutePath.normalize)
  override val inlineRuntimes=InlineRuntime.read(settings.path("inline_runtimes"))
  def maxConcurrentTasks:Int=settings.path("max_concurrent_tasks").asInt(2)
  val taskSlots=new java.util.concurrent.Semaphore(maxConcurrentTasks)
  def executionTimeoutSeconds:Long=settings.path("execution_timeout_seconds").asLong(1800)
  def localOperatorLibrary:Path=Option(settings.get("local_operator_library")).map(n=>Path.of(n.asText)).getOrElse(Path.of(settings.path("operator_cache").asText(".xstream-operators"),"library"))
  def operatorCache:Path=Path.of(settings.path("operator_cache").asText(".xstream-operators"))
  def operatorMaxBytes:Long=settings.path("operator_max_bytes").asLong(8L*1024*1024*1024)
  def remoteOperatorLibraryUrls:Seq[String]={import scala.jdk.CollectionConverters._;settings.path("remote_operator_library_urls").elements.asScala.map(_.asText).toVector}
  val inferenceWorkerPool=new InferenceWorkerPool(InferenceWorkerSettings())
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
  def close():Unit={try {engine.close();inferenceWorkerPool.close();checkpointStore.foreach(_.close());executionHistory.close()}finally {try allocator.close()finally catalogHost.close()}}
}
