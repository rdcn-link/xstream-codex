package xstream.execution

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import com.fasterxml.jackson.databind.JsonNode
import xstream.data.Data

/** Bounded local control channel. Operator data still travels as Arrow streams. */
object SinkProtocol {
  val SocketName="sink-control.sock"
  val MaxBytes=8192
  private def transfer(channel:SocketChannel,buffer:ByteBuffer,write:Boolean,deadline:Long,check:()=>Unit):Unit={
    while(buffer.hasRemaining){
      check()
      if(System.nanoTime()>=deadline)throw new java.util.concurrent.TimeoutException("Sink lifecycle callback timed out")
      val count=if(write)channel.write(buffer)else channel.read(buffer)
      if(count<0)throw new java.io.EOFException("Sink control channel closed")
      if(count==0)Thread.sleep(5)
    }
  }
  def write(channel:SocketChannel,value:JsonNode,deadline:Long,check:()=>Unit=()=>()):Unit={
    val bytes=Data.mapper.writeValueAsBytes(value);require(bytes.nonEmpty && bytes.length<=MaxBytes,"Sink control message exceeds limit")
    val buffer=ByteBuffer.allocate(4+bytes.length);buffer.putInt(bytes.length).put(bytes).flip()
    transfer(channel,buffer,true,deadline,check)
  }
  def read(channel:SocketChannel,deadline:Long,check:()=>Unit=()=>()):JsonNode={
    val size=ByteBuffer.allocate(4);transfer(channel,size,false,deadline,check);size.flip()
    val count=size.getInt;require(count>0 && count<=MaxBytes,"invalid Sink control message length")
    val body=ByteBuffer.allocate(count);transfer(channel,body,false,deadline,check)
    val value=Data.mapper.readTree(body.array());require(value!=null && value.isObject,"invalid Sink control message");value
  }
  def message(state:String):com.fasterxml.jackson.databind.node.ObjectNode=Data.mapper.createObjectNode().put("state",state)
}
