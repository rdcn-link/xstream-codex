package xstream.engine

import _root_.xstream.execution.{FrameSource,StreamCancel}

import _root_.xstream.operator.inference._

import org.apache.arrow.memory.BufferAllocator
/** Host boundary: the engine never starts a data server or owns host credentials. */
trait EngineEnvironment extends OperatorEnvironment {
  /** None for remote-serving hosts; local paths are a standalone-engine capability. */
  def localOperatorBase:Option[java.nio.file.Path]=None
  def inlineRuntimes:Map[String,InlineRuntime]=Map.empty
  def executionResourceLimits:ExecutionResourceLimits=ExecutionResourceLimits()
  def operatorFusion:String
  def maxConcurrentTasks:Int
  def taskSlots:java.util.concurrent.Semaphore
  def executionTimeoutSeconds:Long
  def checkpointStore:Option[_root_.xstream.execution.CheckpointStore]=None
  def executionHistory:ExecutionHistory
  def openItem(allocator:BufferAllocator,binding:InputBinding,cancel:StreamCancel):FrameSource
}
