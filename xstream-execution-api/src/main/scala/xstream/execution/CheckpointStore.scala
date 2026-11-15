package xstream.execution

import xstream.data.Plugin
import com.fasterxml.jackson.databind.JsonNode

/** Complete output streams. No process snapshots or cross-execution replay. */
trait CheckpointStore extends AutoCloseable {
  def begin(key:String,ports:Set[String],check:()=>Unit=()=>()):CheckpointWriter
  def get(key:String):Option[CheckpointData]
  def remove(key:String):Unit
}
trait CheckpointWriter extends AutoCloseable {
  def append(port:String,frame:OperatorFrame):Unit
  def commit():Unit
}
trait CheckpointData extends AutoCloseable {
  def bytes:Long
  def ports:Set[String]
  def open(port:String,check:()=>Unit=()=>()):FrameSource
}
trait CheckpointStoreProvider extends Plugin {
  def open(settings:Map[String,Object]):CheckpointStore
}
case class CheckpointStoreDefinition(provider:String,settings:Map[String,Object]) {
  override def toString:String=s"CheckpointStoreDefinition($provider,<private settings>)"
}
