package xstream.engine

import _root_.xstream.execution.{FrameQueue,FrameSource,OperatorFrame,StreamCancel,StreamProtocol,SinkTransactions,SinkParticipant,ResultSink,SinkCommitException,SinkDiscardException}

import _root_.xstream.data._

import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.Json
import scala.jdk.CollectionConverters._

/** Single-node DAG execution. Storage and operator implementations remain plugins. */
final class LocalJobExecution(config:EngineEnvironment,executor:TaskExecutor,
  source:(InputBinding,StreamCancel)=>FrameSource,allocator:org.apache.arrow.memory.BufferAllocator,
  preparedUnits:ExecutionUnits=ExecutionUnits(JobPlan(Vector.empty,Map.empty),Map.empty,Json.obj())) {
  def run(plan:JobPlan,policy:ExecutionPolicy,record:ExecutionHistory#Record,
    emit:OperatorFrame=>Unit,root:StreamCancel,resultSink:Option[ResultSink]=None):Unit={
    val checkpoints=if(policy.checkpoints.nonEmpty)Some(new JobCheckpoints(config.checkpointStore.getOrElse(throw new IllegalArgumentException("checkpoints require checkpoint_store"))))else None
    try runAttempts(plan,policy,record,emit,root,resultSink,checkpoints)finally checkpoints.foreach(_.close())
  }
  private def runAttempts(plan:JobPlan,policy:ExecutionPolicy,record:ExecutionHistory#Record,
    emit:OperatorFrame=>Unit,root:StreamCancel,resultSink:Option[ResultSink],checkpoints:Option[JobCheckpoints]):Unit={
    val execution=Json.obj().put("operator_fusion",config.operatorFusion)
    if(policy.checkpoints.nonEmpty)execution.put("fusion_reason","checkpoint_boundary")
    else if(policy.maxRetries>0)execution.put("fusion_reason","retry_boundary")
    else if(config.operatorFusion=="isolated")execution.put("fusion_reason","isolated_by_policy")
    if(preparedUnits.description.has("fusion_decisions"))execution.set[com.fasterxml.jackson.databind.JsonNode]("fusion_decisions",preparedUnits.description.get("fusion_decisions"))
    val units=execution.putArray("execution_units")
    plan.steps.foreach { t=>val unit=units.addObject().put("id",t.id).put("mode",if(t.operator==Jobs.ItemSource)"host_stream_source"else "isolated_step");unit.putArray("steps").add(t.id) }
    record.update("running",if(preparedUnits.groups.nonEmpty)preparedUnits.description else execution)
    val outgoing=ExecutionPolicy.connected(plan).groupBy(_._1).view.mapValues(_.map(_._2)).toMap
    val attempts=Json.mapper.createArrayNode()
    val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(policy.timeoutSeconds.getOrElse(config.executionTimeoutSeconds))
    var retry=0
    var attempt=Json.uuid()
    while(true){
      root.active()
      val token=new StreamCancel(()=>{root.checkState();if(System.nanoTime()>=deadline)throw new java.util.concurrent.TimeoutException("Job execution timed out");false},policy.timeoutSeconds.getOrElse(config.executionTimeoutSeconds))
      root.add(token)
      val sinks=new SinkTransactions
      resultSink.foreach { result=>sinks.register(new SinkParticipant {
        val id="client_sink";override val commitOrder=Int.MaxValue
        def onComplete():Unit=result.onComplete()
        def onDiscard(cause:Throwable):Unit=result.onDiscard(cause)
        def close():Unit=()
      }) }
      val threads=scala.collection.mutable.ArrayBuffer.empty[Thread]
      val attemptRecord=attempts.addObject().put("attempt_id",attempt).put("retry_index",retry).put("state","running").put("started_at_ms",Json.now)
      val details=Json.obj().put("attempt_id",attempt).put("retry_count",retry).put("max_retries",policy.maxRetries)
      details.set[JsonNode]("attempts",attempts)
      record.update(if(retry==0)"running"else "retrying",details)
      try {
        val edges=plan.steps.flatMap(t=>t.inputs.toVector.flatMap{case(p,b)=>b.task.map(up=>(up,b.port,t.id,p))})
        require(edges.map(e=>e._1->e._2).distinct.size==edges.size,"fan-out requires an explicit duplicating operator")
        require(plan.outputs.values.toSet.size==plan.outputs.size && !plan.outputs.values.exists(p=>edges.exists(e=>(e._1,e._2)==p)),"output is consumed more than once")
        require(plan.steps.count(_.operator!=Jobs.ItemSource)<=config.maxConcurrentTasks,"pipeline needs more local task slots; increase max_concurrent_tasks")
        val queues=edges.map(e=>(e._3,e._4)->new FrameQueue(token)).toMap
        val terminal=new FrameQueue(token)
        val completed=java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
        val available=checkpoints.map(_.available).getOrElse(Set.empty[String])
        val needed=CheckpointRecovery.needed(plan,policy.checkpoints,available)
        val reused=needed.filter(id=>available(id) && outgoing.getOrElse(id,Set.empty).forall(p=>policy.checkpoints(id->p)))
        val recovery=Json.obj();recovery.putArray("reused_checkpoints").addAll(reused.toVector.sorted.map(Json.mapper.getNodeFactory.textNode).asJava)
        checkpoints.foreach(c=>recovery.set[JsonNode]("checkpoints",c.snapshot));record.update("running",recovery)
        plan.steps.filter(t=>needed(t.id)).foreach { t=>
          val child=config.executionHistory.begin(if(preparedUnits.groups.contains(t.id))"execution_unit"else "job_task",metadata=Json.obj().put("parent_execution_id",record.id).put("task_id",t.id).put("attempt_id",attempt).put("operator",t.operator),state="waiting_inputs")
          val stepDetails=Json.obj();t.title.foreach(stepDetails.put("title",_));val inputDetails=stepDetails.putObject("inputs");t.inputs.foreach{case(p,b)=>val n=inputDetails.putObject(p).put("port",b.port).put("view",b.view);b.task.foreach(n.put("task",_));b.uri.foreach(n.put("uri",_))};child.update("waiting_inputs",stepDetails)
          threads+=StreamProtocol.thread(token){
            val counters=new java.util.concurrent.ConcurrentHashMap[String,PortMetrics]()
            def observe(direction:String,port:String,f:OperatorFrame):Unit=counters.computeIfAbsent(direction+"/"+port,_=>new PortMetrics(allocator)).observe(f,remote=false)
            def metrics:com.fasterxml.jackson.databind.node.ObjectNode={val n=Json.obj();val ps=n.putObject("ports");counters.asScala.foreach{case(k,v)=>ps.set[JsonNode](k,v.snapshot)};n}
            val begun=scala.collection.mutable.Set.empty[String];val ended=scala.collection.mutable.Set.empty[String]
            val selected=policy.checkpoints.collect{case(id,port)if id==t.id=>port}
            var writer:Option[_root_.xstream.execution.CheckpointWriter]=None
            def deliver(f:OperatorFrame):Unit={
              val port=StreamProtocol.header(f).path("port").asText
              edges.find(e=>e._1==t.id && e._2==port) match {
                case Some(e)=>queues(e._3,e._4).put(StreamProtocol.rename(f,e._4))
                case None=>plan.outputs.find(_._2==(t.id,port)).foreach{case(label,_)=>terminal.put(StreamProtocol.rename(f,label,t.id))}
              }
            }
            def output(f:OperatorFrame):Unit={
              token.active();val h=StreamProtocol.header(f);val port=h.path("port").asText
              begun.synchronized { h.path("type").asText match {
                case "start"=>require(!begun(port),"duplicate output start");begun+=port
                case "data"=>require(begun(port) && !ended(port),"invalid output data")
                case "end"=>require(begun(port) && !ended(port),"invalid output end");ended+=port
                case _=>throw new IllegalArgumentException("invalid operator output event")
              }
              }
              observe("outputs",port,f)
              if(selected(port))writer.get.append(port,f)else deliver(f)
            }
            try {
              child.update("running",Json.obj().put("checkpoint_reused",reused(t.id)))
              if(reused(t.id)){
                checkpoints.get.replay(t.id,selected,token)(deliver)
              }else {
                  if(selected.nonEmpty)writer=Some(checkpoints.get.begin(t.id,selected,()=>token.active()))
                  val inputs=scala.collection.mutable.Map.empty[String,FrameSource]
                  val acquired=t.operator!=Jobs.ItemSource
                  if(acquired)require(config.taskSlots.tryAcquire(),"node task capacity reached")
                  try {
                    t.inputs.foreach{case(p,b)=>
                      val raw=b.uri.map(_=>source(b,token)).getOrElse(queues(t.id,p))
                      inputs(p)=new FrameSource {
                        def next():Option[OperatorFrame]={val f=raw.next();f.foreach(observe("inputs",p,_));f}
                        def close():Unit=raw.close()
                      }
                    }
                    Jobs.readBinding(t) match {
                      case Some(b)=>val stream=token.add(source(b,token));try {var f=stream.next();while(f.nonEmpty){output(StreamProtocol.rename(f.get,"result"));f=stream.next()}}finally stream.close()
                      case None=>
                        val group=preparedUnits.groups.get(t.id)
                        val members=group.toVector.flatMap(_.operator.command.files.get("group.json")).flatMap(bytes=>Data.mapper.readTree(bytes).path("steps").elements.asScala.map(_.path("id").asText)).map { id=>
                          id->config.executionHistory.begin("job_task",metadata=Json.obj().put("parent_execution_id",record.id).put("task_id",id).put("execution_mode","shared_process"),state="waiting_inputs")
                        }.toMap
                        def event(h:JsonNode):Unit={
                          val id=h.path("task").asText
                          require(members.contains(id),"unknown group step")
                          if(h.path("type").asText=="task_state")members(id).update(h.path("state").asText,Json.obj().put("runtime_pid",h.path("runtime_pid").asLong))
                        }
                        try executor.execute(t,inputs.toMap,output,token,resolved=group.map(_.operator),onEvent=group.map(_=>event _),onRuntime=Some(runtime=>child.update("running",runtime)),resources=policy.resources,sinks=Some(sinks),attemptId=attempt,jobId=record.id)
                        catch{case error:Throwable=>members.values.foreach(_.update("failed",message=Option(error.getMessage)));throw error}
                    }
                  }finally {inputs.values.foreach(_.close());if(acquired)config.taskSlots.release()}
              require(outgoing.getOrElse(t.id,Set.empty).subsetOf(ended.toSet),"missing output end")
              token.active();writer.foreach(_.commit())
              checkpoints.foreach { c=>val status=Json.obj();status.set[JsonNode]("checkpoints",c.snapshot);record.update("running",status) }
              if(selected.nonEmpty){
                checkpoints.get.replay(t.id,selected,token)(deliver)
              }
              }
              token.active();child.update("succeeded",metrics);completed.add(t.id)
            }catch{case e:Throwable=>child.update(token.cause(e) match {case _:java.util.concurrent.TimeoutException=>"expired";case _:java.util.concurrent.CancellationException=>"cancelled";case _=>"failed"},metrics,message=Some(Option(e.getMessage).getOrElse("step failed")));throw e}finally writer.foreach(w=>try w.close()catch{case scala.util.control.NonFatal(t)=>throw new SinkDiscardException("Checkpoint cleanup failed; automatic retry is forbidden",t)})
          }
        }
        val workers=threads.toVector
        val completion=StreamProtocol.thread(token){workers.foreach(waitFor(_,token));terminal.end()};threads+=completion
        var f=terminal.next();while(f.nonEmpty){emit(f.get);f=terminal.next()};waitFor(completion,token)
        root.active();token.active()
        record.update("committing")
        sinks.onComplete(()=>{root.active();token.active()})
        val committed=Json.obj();val sinkStates=committed.putObject("sinks");sinks.states.foreach{case(k,v)=>sinkStates.put(k,v)};record.update("running",committed)
        attemptRecord.put("state","succeeded").put("finished_at_ms",Json.now)
        val finalDetails=Json.obj();finalDetails.set[JsonNode]("attempts",attempts);record.update("running",finalDetails)
        return
      }catch{case caught:Throwable=>
        val failure=token.cause(caught)
        attemptRecord.put("state",failure match {case _:java.util.concurrent.TimeoutException=>"expired";case _:java.util.concurrent.CancellationException=>"cancelled";case _=>"failed"}).put("finished_at_ms",Json.now).put("message",Option(failure.getMessage).getOrElse("attempt failed").take(2048))
        val failedDetails=Json.obj();failedDetails.set[JsonNode]("attempts",attempts);record.update("running",failedDetails)
        token.close();threads.foreach(_.join(5000))
        try sinks.onDiscard(failure)catch{case cleanup:SinkDiscardException=>cleanup.addSuppressed(failure);throw cleanup}
        require(!threads.exists(_.isAlive),"previous attempt did not stop")
        val discarded=Json.obj();val sinkStates=discarded.putObject("sinks");sinks.states.foreach{case(k,v)=>sinkStates.put(k,v)};record.update("running",discarded)
        root.active()
        val nonretry=sinks.commitStarted || failure.isInstanceOf[SinkCommitException] || failure.isInstanceOf[SinkDiscardException] || failure.isInstanceOf[IllegalArgumentException] || failure.isInstanceOf[java.util.concurrent.TimeoutException] || failure.isInstanceOf[java.util.concurrent.CancellationException] || Option(failure.getMessage).exists(_.contains("FORBIDDEN"))
        if(retry>=policy.maxRetries || nonretry)throw failure
        val previous=attempt;attempt=Json.uuid();retry+=1
        val h=Json.obj().put("type","attempt_failed").put("port","").put("attempt_id",previous).put("next_attempt_id",attempt).put("retry_count",retry).put("message",Option(failure.getMessage).getOrElse("attempt failed"))
        emit(OperatorFrame(Json.mapper.writeValueAsBytes(h)))
      }finally {token.close();threads.foreach(_.join(5000))}
    }
  }
  private def waitFor(t:Thread,c:StreamCancel):Unit={while(t.isAlive){c.active();t.join(50)};c.active()}
}
