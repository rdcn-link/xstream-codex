package xstream.execution

import _root_.xstream.execution.{OperatorFrame,FrameSource,StreamCancel}
import xstream.data.{Plugin,Scan}
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.memory.BufferAllocator

final case class RemoteItemRequest(uri:String,view:String,scan:Option[Scan],selection:Option[JsonNode])
/** Data access is independent of permission to execute a Job. */
trait RemoteItemProvider extends Plugin {
  def supports(uri:String):Boolean
  def open(settings:JsonNode,request:RemoteItemRequest,allocator:BufferAllocator,cancel:StreamCancel):FrameSource
}
