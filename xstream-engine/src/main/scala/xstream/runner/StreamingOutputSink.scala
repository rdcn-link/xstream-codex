package xstream.runner

import java.io.{ByteArrayInputStream,OutputStream}
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import xstream.execution.{OperatorFrame,ResultSink,StreamProtocol}
import xstream.util.Json
import scala.jdk.CollectionConverters._
import scala.util.Using

/** Streams provisional output immediately; the receiver owns failed-attempt discard. */
private[runner] final class StreamingOutputSink(allocator:BufferAllocator,output:OutputStream,
  discardOutput:Throwable=>Unit) extends ResultSink {
  private val representations=scala.collection.mutable.Map.empty[String,String]
  private def sampleValue(value:Any):com.fasterxml.jackson.databind.JsonNode=value match {
    case null=>Json.mapper.nullNode()
    case v:org.apache.arrow.vector.util.Text=>Json.mapper.getNodeFactory.textNode(v.toString)
    case v:java.util.Map[_,_]=>val n=Json.obj();v.asScala.foreach{case(k,x)=>n.set[com.fasterxml.jackson.databind.JsonNode](k.toString,sampleValue(x))};n
    case v:java.util.List[_]=>val n=Json.mapper.createArrayNode();v.asScala.foreach(x=>n.add(sampleValue(x)));n
    case v:java.time.temporal.TemporalAccessor=>Json.mapper.getNodeFactory.textNode(v.toString)
    case v:Array[Byte]=>Json.mapper.getNodeFactory.textNode(java.util.Base64.getEncoder.encodeToString(v))
    case v=>Json.mapper.valueToTree[com.fasterxml.jackson.databind.JsonNode](v)
  }

  def accept(frame:OperatorFrame):Unit={
    val h=StreamProtocol.header(frame)
    val port=h.path("port").asText
    h.path("type").asText match {
      case "start"=>representations(port)=h.path("representation").asText
      case "data"=>
        if(representations.get(port).contains("records")){
          Using.resource(new ArrowStreamReader(new ByteArrayInputStream(frame.data),allocator)){reader=>
            while(reader.loadNextBatch()){
              val root=reader.getVectorSchemaRoot
              (0 until root.getRowCount).foreach { i=>
                val row=Json.obj()
                root.getFieldVectors.asScala.foreach(v=>row.set[com.fasterxml.jackson.databind.JsonNode](v.getName,sampleValue(v.getObject(i))))
                output.write((row.toString+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
              }
            }
          }
        }else output.write(frame.data)
        output.flush()
      case _=>()
    }
  }
  def onDiscard(cause:Throwable):Unit={representations.clear();discardOutput(cause)}
  def onComplete():Unit=output.flush()
  // stdout and other caller-owned streams must remain open.
  def close():Unit=output.flush()
}

private[runner] object StreamingOutputSink {
  def stdout(allocator:BufferAllocator,output:OutputStream,errors:java.io.PrintStream):StreamingOutputSink=
    new StreamingOutputSink(allocator,output,cause=>{
      output.write("\n**** XSTREAM: ATTEMPT DISCARDED ****\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      output.flush()
      errors.println("Job attempt discarded; previously streamed stdout belongs to the failed attempt: "+Option(cause.getMessage).getOrElse("execution failed"))
    })
}
