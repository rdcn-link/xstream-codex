package xstream.engine

import _root_.xstream.execution._
import _root_.xstream.util.Json
import com.fasterxml.jackson.databind.JsonNode

/** An embedded local engine uses the same lifecycle contract as a remote execution target. */
final class LocalExecutionTarget(engine:JobEngine,val id:String="local") extends ExecutionTarget {
  def describe(cancel:StreamCancel):TargetDescription={cancel.active();TargetDescription(id,true)}
  def localize(uri:String):Option[String]=None
  def submit(request:ExecutionRequest,parent:StreamCancel):ExecutionHandle={
    parent.active()
    val plan=Jobs.parse(request.jobYaml)
    require(plan.steps.forall(_.node.isEmpty),"local execution cannot assign nodes")
    plan.steps.foreach(Jobs.authorizeSource(engine.environment,_))
    val raw=request.policy.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
    raw.putObject("placement").put("mode","local")
    val policy=ExecutionPolicy.parse(raw,plan)
    val timeout=math.min(request.timeoutSeconds,math.min(parent.remainingSeconds,policy.timeoutSeconds.getOrElse(request.timeoutSeconds)))
    val token=parent.add(new StreamCancel(()=>{parent.active();false},timeout))
    val queue=new FrameQueue(token)
    val record=engine.environment.executionHistory.begin("local_job",metadata=Json.obj().put("name",plan.name).put("job_yaml",request.jobYaml))
    @volatile var state="running"
    @volatile var message:Option[String]=None
    val worker=StreamProtocol.thread(token){
      try {
        engine.execute(plan,policy,"local",record,None,queue.put,token)
        state="succeeded";record.update(state);queue.end()
      }catch{case scala.util.control.NonFatal(t)=>
        state=token.cause(t) match {case _:java.util.concurrent.TimeoutException=>"expired";case _:java.util.concurrent.CancellationException=>"cancelled";case _=>"failed"}
        message=Option(t.getMessage);record.update(state,message=message);throw t
      }
    }
    parent.add(new ExecutionHandle {
      def next():Option[OperatorFrame]=queue.next()
      def status():ExecutionStatus=ExecutionStatus(state,Some(record.id),message)
      def cancel():Unit=token.close()
      def close():Unit={token.close();worker.interrupt();if(Thread.currentThread()!=worker)worker.join(5000)}
    })
  }
}
