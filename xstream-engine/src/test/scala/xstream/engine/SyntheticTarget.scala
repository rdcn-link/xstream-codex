package xstream.engine

import _root_.xstream.execution._
/** Test transport implemented without any DACP client or protocol classes. */
abstract class SyntheticTarget extends ExecutionTarget {
  val id="remote"
  val nodes=Set("dacp://remote:18861")
  def describe(cancel:StreamCancel):TargetDescription=TargetDescription(id,true,estimatedInputBytes=Some(64000000),estimatedOutputBytes=Some(1000000))
  def localize(uri:String):Option[String]=nodes.find(uri.startsWith).map(n=>"context:"+java.net.URI.create(uri).getRawPath)
  def execute(node:String,plan:JobPlan,emit:OperatorFrame=>Unit,cancel:StreamCancel,onExecution:String=>Unit):Unit
  def submit(request:ExecutionRequest,cancel:StreamCancel):ExecutionHandle={
    val queue=new FrameQueue(cancel)
    @volatile var state="running"
    @volatile var execution:Option[String]=None
    val worker=StreamProtocol.thread(cancel){execute(id,Jobs.parse(request.jobYaml),queue.put,cancel,id=>execution=Some(id));state="succeeded";queue.end()}
    new ExecutionHandle {
      def next():Option[OperatorFrame]=queue.next()
      def status():ExecutionStatus=ExecutionStatus(state,execution)
      def cancel():Unit={worker.interrupt();state="cancelled"}
      def close():Unit=if(state!="succeeded")cancel()
    }
  }
}
