package xstream.engine

import _root_.xstream.execution.{FrameSource,MaterializationStore,MaterializationStoreDefinition,StreamCancel}

import _root_.xstream.operator.inference._

import org.apache.arrow.memory.BufferAllocator
/** Host boundary: the engine never starts a data server or owns host credentials. */
trait EngineEnvironment extends OperatorEnvironment {
  def executionTargets:Seq[_root_.xstream.execution.ExecutionTarget]=Seq.empty
  /** None for remote-serving hosts; local paths are a standalone-engine capability. */
  def localOperatorBase:Option[java.nio.file.Path]=None
  def debug:Boolean
  def sourceRuntimes:Map[String,SourceRuntime]
  def operatorFusionEnabled:Boolean
  def maxConcurrentTasks:Int
  def taskSlots:java.util.concurrent.Semaphore
  def executionTimeoutSeconds:Long
  def executionHistory:ExecutionHistory
  def materializationStore:Option[MaterializationStoreDefinition]
  def openMaterialization(d:MaterializationStoreDefinition):MaterializationStore
  def openItem(allocator:BufferAllocator,binding:InputBinding,cancel:StreamCancel):FrameSource
}
