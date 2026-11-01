package xstream.engine

import _root_.xstream.execution.{FrameSource,OperatorFrame,StreamCancel,StreamProtocol}

import _root_.xstream.data._

import org.apache.arrow.memory.BufferAllocator
import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.Json
import scala.jdk.CollectionConverters._

/** Embeddable streaming engine. Catalog and runtime lifecycles belong to its host. */
final class JobEngine(val environment:EngineEnvironment,val allocator:BufferAllocator,itemSource:Option[(InputBinding,StreamCancel)=>FrameSource]=None,suppliedExecutor:Option[TaskExecutor]=None) extends AutoCloseable {
  private val active=java.util.concurrent.ConcurrentHashMap.newKeySet[StreamCancel]()
  private val closed=new java.util.concurrent.atomic.AtomicBoolean()
  def close():Unit=if(closed.compareAndSet(false,true))active.asScala.foreach(_.close())
  private def openSource(b:InputBinding,c:StreamCancel):FrameSource=itemSource.map(_(b,c)).getOrElse(environment.openItem(allocator,b,c))
  def dataCatalog:DataCatalog=DataCatalogRuntime.catalog
  val executor=suppliedExecutor.getOrElse(new TaskExecutor(environment,allocator))
  def execute(plan:JobPlan,policy:ExecutionPolicy,owner:String,record:ExecutionHistory#Record,replay:Option[String],emit:OperatorFrame=>Unit,cancel:StreamCancel):Unit={
    require(!closed.get(),"JobEngine is closed");active.add(cancel)
    try {
      if(closed.get())cancel.close()
      cancel.active()
      if(policy.maxRetries==0 && policy.materialized.isEmpty && replay.isEmpty && fused(plan,record,emit,cancel))() else {
        val units=if(policy.maxRetries==0 && policy.materialized.isEmpty && replay.isEmpty)ExecutionUnits.prepare(environment,plan,()=>cancel.active())else ExecutionUnits(plan,Map.empty,Json.obj())
        val effective=if(units.groups.nonEmpty)ExecutionPolicy.parse(Json.obj(),units.plan)else policy
        new LocalJobExecution(environment,executor,(b,c)=>openSource(b,c),allocator,preparedUnits=units).run(units.plan,effective,owner,record,replay,emit,cancel)
      }
    }finally active.remove(cancel)
  }
  private def fused(plan:JobPlan,record:ExecutionHistory#Record,emit:OperatorFrame=>Unit,c:StreamCancel):Boolean={
    val config=environment;val permits=environment.taskSlots
    def source(b:InputBinding,c:StreamCancel):FrameSource=openSource(b,c)
      OperatorGroups.prepare(config,plan,()=>c.active()) match {
        case Right(group)=>
          require(permits.tryAcquire(),"node task capacity reached")
          try {
            val execution=Json.obj().put("execution_mode","shared_process").put("runtime_image",group.operator.image).put("step_count",plan.steps.size)
            val units=execution.putArray("execution_units")
            val unit=units.addObject().put("id","shared").put("mode","shared_process").put("runtime_image",group.operator.image)
            _root_.xstream.operator.inference.OperatorLibrary.runtimeDescriptor(group.operator).fields.asScala.foreach(e=>unit.set[JsonNode](e.getKey,e.getValue))
            val members=unit.putArray("steps");plan.steps.filterNot(t=>group.transportSources.values.toSet(t.id)).foreach(t=>members.add(t.id))
            group.transportSources.values.toSet.toVector.sorted.foreach { id=>val u=units.addObject().put("id",id).put("mode","host_stream_source");u.putArray("steps").add(id) }
            record.update("running",execution)
            val children=plan.steps.map { t=>t.id->config.executionHistory.begin("job_task",metadata=_root_.xstream.util.Json.obj().put("parent_execution_id",record.id).put("task_id",t.id).put("operator",t.operator).put("execution_mode","shared_process"),state="waiting_inputs") }.toMap
            val counters=new java.util.concurrent.ConcurrentHashMap[String,PortMetrics]()
            val completed=java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
            def onEvent(h:com.fasterxml.jackson.databind.JsonNode):Unit={
              val id=h.path("task").asText;require(children.contains(id),"unknown group task")
              if(h.path("type").asText=="task_state"){
                val state=h.path("state").asText;require(Set("running","succeeded")(state),"invalid group state")
                val detail=_root_.xstream.util.Json.obj().put("runtime_pid",h.path("runtime_pid").asLong);val ports=detail.putObject("ports");counters.asScala.filter(_._1.startsWith(id+"/")).foreach{case(k,v)=>ports.set[com.fasterxml.jackson.databind.JsonNode](k.stripPrefix(id+"/"),v.snapshot)}
                children(id).update(state,detail);if(state=="succeeded")completed.add(id)
              }else counters.computeIfAbsent(id+"/"+h.path("direction").asText+"/"+h.path("port").asText,_=>new PortMetrics(allocator)).summary(h)
            }
            val external=plan.steps.flatMap(t=>t.inputs.toVector.sortBy(_._1).collect{case(port,b)if b.uri.nonEmpty=>(t.id,port)}).zipWithIndex.map{case(pair,i)=>s"i$i"->pair}.toMap
            val inputs=scala.collection.mutable.Map.empty[String,FrameSource]
            try {
              group.inputs.foreach {case(port,b)=>
                val raw=source(b,c);val (task,name)=group.transportSources.get(port).map(_->"result").getOrElse(external(port))
                group.transportSources.get(port).foreach(id=>children(id).update("running"))
                inputs(port)=new FrameSource {
                  def next():Option[OperatorFrame]={val f=raw.next();f.foreach { x=>
                    counters.computeIfAbsent(task+(if(group.transportSources.contains(port))"/outputs/"else "/inputs/")+name,_=>new PortMetrics(allocator)).observe(x,remote=false)
                    if(StreamProtocol.header(x).path("type").asText=="end")group.transportSources.get(port).foreach { id=>children(id).update("succeeded");completed.add(id) }
                  };f}
                  def close():Unit=raw.close()
                }
              }
              executor.execute(group.task,inputs.toMap,f=>{
              val port=StreamProtocol.header(f).path("port").asText;val (task,label)=group.outputs(port)
              emit(StreamProtocol.rename(f,label,task))
            },c,Some(group.operator),Some(onEvent))
            }
            catch{case t:Throwable=>
              val reason=c.cause(t) match {case _:java.util.concurrent.TimeoutException=>"expired";case _:java.util.concurrent.CancellationException=>"cancelled";case _=>"failed"}
              children.foreach{case(id,r)=>if(!completed.contains(id))r.update(reason,message=Some(Option(t.getMessage).getOrElse("group failed")))};throw t
            }
            finally inputs.values.foreach(_.close())
          }finally permits.release()
          return true
        case Left(reason)=>record.update("running",_root_.xstream.util.Json.obj().put("execution_mode","isolated_steps").put("fusion_reason",reason))
      }
    false
  }
  def run(yaml:String,emit:OperatorFrame=>Unit,cancel:StreamCancel,policy:JsonNode=Json.obj()):String={
    val plan=Jobs.parse(yaml);require(plan.steps.forall(_.node.isEmpty),"Local jobs cannot assign execution nodes")
    plan.steps.foreach(Jobs.authorizeSource(environment,_))
    val record=environment.executionHistory.begin("local_job",metadata=Json.obj().put("name",plan.name).put("job_yaml",yaml))
    try {execute(plan,ExecutionPolicy.parse(policy,plan),"local",record,None,emit,cancel);record.update("succeeded");record.id}
    catch{case scala.util.control.NonFatal(e)=>record.update(cancel.cause(e) match {case _:java.util.concurrent.TimeoutException=>"expired";case _:java.util.concurrent.CancellationException=>"cancelled";case _=>"failed"},message=Option(e.getMessage));throw e}
  }
}
