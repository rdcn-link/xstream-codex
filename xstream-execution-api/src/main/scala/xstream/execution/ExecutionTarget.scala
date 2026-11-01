package xstream.execution

import _root_.xstream.execution.{OperatorFrame,FrameSource,StreamCancel}
import xstream.data.{Plugin,Scan}
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.memory.BufferAllocator

/** A host-selected execution location. Descriptions reflect the current caller, not just service features. */
final case class TargetDescription(id:String, canSubmit:Boolean, available:Boolean=true,
  startupMillis:Long=0, transferBytesPerSecond:Long=100000000, reason:Option[String]=None,estimatedInputBytes:Option[Long]=None,estimatedOutputBytes:Option[Long]=None) {
  require(id.nonEmpty && startupMillis>=0 && transferBytesPerSecond>0 && estimatedInputBytes.forall(_>=0) && estimatedOutputBytes.forall(_>=0),"invalid execution target description")
}
final case class ExecutionRequest(jobYaml:String,policy:JsonNode,timeoutSeconds:Long) {
  require(timeoutSeconds>0,"execution timeout must be positive")
}
final case class ExecutionStatus(state:String,id:Option[String],message:Option[String]=None)
/** Closing an unfinished handle cancels its result subscription and remote computation. */
trait ExecutionHandle extends FrameSource {
  def status():ExecutionStatus
  def cancel():Unit
}
trait ExecutionTarget extends AutoCloseable {
  def id:String
  def describe(cancel:StreamCancel):TargetDescription
  /** Return a receiving context URI only when this target owns the data address. Never infer from host names in core. */
  def localize(uri:String):Option[String]
  def submit(request:ExecutionRequest,cancel:StreamCancel):ExecutionHandle
  def close():Unit=()
}
trait ExecutionTargetProvider extends Plugin {
  def create(name:String,settings:JsonNode):ExecutionTarget
}
final case class RemoteItemRequest(uri:String,view:String,scan:Option[Scan],selection:Option[JsonNode])
/** Data access is independent of permission to execute a Job. */
trait RemoteItemProvider extends Plugin {
  def supports(uri:String):Boolean
  def open(settings:JsonNode,request:RemoteItemRequest,allocator:BufferAllocator,cancel:StreamCancel):FrameSource
}
