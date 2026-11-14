package xstream.execution

import xstream.data._
import com.fasterxml.jackson.databind.JsonNode
import java.io.{DataInputStream,DataOutputStream}
import java.nio.channels.{Channels,SocketChannel}
import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.nio.file.Path
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader

/** Private per-step IPC. Credentials and network connections never enter the operator. */
object ItemReadProtocol {
  val SocketName="item-access.sock"
  val MaxRequestBytes=65536
  def read(in:DataInputStream):JsonNode={val n=in.readInt();require(n>0 && n<=MaxRequestBytes,"Item access message too large");val bytes=new Array[Byte](n);in.readFully(bytes);Data.mapper.readTree(bytes)}
  def write(out:DataOutputStream,n:JsonNode):Unit={val bytes=Data.mapper.writeValueAsBytes(n);require(bytes.length<=MaxRequestBytes,"Item access message too large");out.writeInt(bytes.length);out.write(bytes);out.flush()}
  def open(socket:Path,uri:String,view:String,selection:JsonNode,allocator:BufferAllocator):ArrowDataStream={
    val params=Data.mapper.createObjectNode().put("uri",uri).put("view",view)
    if(selection!=null)params.set[JsonNode]("selection",selection)
    // Validation before contacting the host; the host independently repeats it.
    val mapping=Data.mapper.convertValue(params,classOf[java.util.LinkedHashMap[String,Object]])
    ItemSourceParameters.address(mapping)
    ArrowDataStream.scoped { (out,active)=>
      val channel=SocketChannel.open(StandardProtocolFamily.UNIX)
      try {
        active();channel.connect(UnixDomainSocketAddress.of(socket))
        write(new DataOutputStream(Channels.newOutputStream(channel)),params)
        val input=new DataInputStream(Channels.newInputStream(channel));val status=read(input)
        require(status.path("ok").asBoolean(false),"Item read failed: "+status.path("error").asText("unavailable"))
        val reader=new ArrowStreamReader(input,allocator)
        try {out.start(reader.getVectorSchemaRoot.getSchema,reader);while({active();reader.loadNextBatch()})out.accept(ArrowBatch(reader.getVectorSchemaRoot,reader));require(read(input).path("complete").asBoolean(false),"incomplete Item read")}
        finally reader.close()
      }finally channel.close()
    }
  }
}
