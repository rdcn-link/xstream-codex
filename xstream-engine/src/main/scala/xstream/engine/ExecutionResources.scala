package xstream.engine

import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.Json

/** Per-container resource request; tmp is a memory-backed filesystem, not disk quota. */
final case class ExecutionResources(tmpSizeMb:Long=512,memoryMb:Long=1024) {
  require(tmpSizeMb>=1 && tmpSizeMb<=1048576,"resources.tmp_size_mb must be between 1 and 1048576")
  require(memoryMb>=128 && memoryMb<=1048576,"resources.memory_mb must be between 128 and 1048576")
  require(tmpSizeMb<memoryMb,"resources.tmp_size_mb must be less than resources.memory_mb; tmpfs shares container memory")
  def json:JsonNode=Json.obj().put("tmp_size_mb",tmpSizeMb).put("memory_mb",memoryMb)
  def tmpMount:String=s"rw,noexec,nosuid,nodev,size=${tmpSizeMb}m"
  def memoryBytes:Long=memoryMb*1024L*1024L
}
object ExecutionResources {
  def parse(n:JsonNode):ExecutionResources={
    Json.fields(n,Set.empty,Set("tmp_size_mb","memory_mb"))
    ExecutionResources(if(n.has("tmp_size_mb"))Json.number(n,"tmp_size_mb",1,1048576)else 512,
      if(n.has("memory_mb"))Json.number(n,"memory_mb",128,1048576)else 1024)
  }
}
final case class ExecutionResourceLimits(maxTmpSizeMb:Long=8192,maxMemoryMb:Long=16384) {
  require(maxTmpSizeMb>=512 && maxTmpSizeMb<=1048576,"max_tmp_size_mb must be between 512 and 1048576")
  require(maxMemoryMb>=1024 && maxMemoryMb<=1048576,"max_memory_mb must be between 1024 and 1048576")
  def validate(r:ExecutionResources):Unit={
    require(r.tmpSizeMb<=maxTmpSizeMb,"resources.tmp_size_mb exceeds host max_tmp_size_mb")
    require(r.memoryMb<=maxMemoryMb,"resources.memory_mb exceeds host max_memory_mb")
  }
}
object ExecutionResourceLimits {
  def parse(n:JsonNode):ExecutionResourceLimits={
    Json.fields(n,Set.empty,Set("max_tmp_size_mb","max_memory_mb"))
    ExecutionResourceLimits(if(n.has("max_tmp_size_mb"))Json.number(n,"max_tmp_size_mb",512,1048576)else 8192,
      if(n.has("max_memory_mb"))Json.number(n,"max_memory_mb",1024,1048576)else 16384)
  }
}
