package xstream.runner

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{IntVector,VectorSchemaRoot}
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.scalatest.funsuite.AnyFunSuite
import xstream.execution.StreamProtocol

class StreamingOutputSinkSpec extends AnyFunSuite {
  test("bytes reach the receiver before completion and discard delegates reset before the next attempt") {
    val allocator=new RootAllocator()
    val output=new ByteArrayOutputStream()
    var discarded:Throwable=null
    val sink=new StreamingOutputSink(allocator,output,cause=>{discarded=cause;output.reset()})
    try {
      sink.accept(StreamProtocol.start("result","bytes","binary"))
      sink.accept(StreamProtocol.frame("data","result","failed-attempt".getBytes(UTF_8)))
      assert(output.toString(UTF_8)=="failed-attempt")
      val failure=new RuntimeException("retry")
      sink.onDiscard(failure)
      assert(discarded eq failure)
      assert(output.size()==0)
      sink.accept(StreamProtocol.start("result","bytes","binary"))
      sink.accept(StreamProtocol.frame("data","result","success".getBytes(UTF_8)))
      assert(output.toString(UTF_8)=="success")
      sink.onComplete()
      assert(output.toString(UTF_8)=="success")
    }finally {sink.close();allocator.close()}
  }

  test("records stream as JSONL and closing the receiver leaves the caller output open") {
    val allocator=new RootAllocator()
    var closed=false
    val output=new ByteArrayOutputStream(){override def close():Unit={closed=true;super.close()}}
    val vector=new IntVector("value",allocator)
    vector.allocateNew(2);vector.setSafe(0,7);vector.setSafe(1,8);vector.setValueCount(2)
    val root=VectorSchemaRoot.of(vector);root.setRowCount(2)
    val encoded=new ByteArrayOutputStream()
    val writer=new ArrowStreamWriter(root,null,encoded)
    val sink=new StreamingOutputSink(allocator,output,_=>())
    try {
      writer.start();writer.writeBatch();writer.end()
      sink.accept(StreamProtocol.start("result","records","arrow"))
      sink.accept(StreamProtocol.frame("data","result",encoded.toByteArray))
      assert(output.toString(UTF_8)=="{\"value\":7}\n{\"value\":8}\n")
      sink.onComplete();sink.close()
      assert(!closed)
    }finally {writer.close();root.close();allocator.close()}
  }
  test("stdout discard emits a separate marker before a retry while success leaves output untouched") {
    val allocator=new RootAllocator()
    val output=new ByteArrayOutputStream()
    val diagnostics=new ByteArrayOutputStream()
    val errors=new java.io.PrintStream(diagnostics,true,UTF_8)
    val sink=StreamingOutputSink.stdout(allocator,output,errors)
    try {
      sink.accept(StreamProtocol.start("result","bytes","binary"))
      sink.accept(StreamProtocol.frame("data","result","partial".getBytes(UTF_8)))
      sink.onDiscard(new RuntimeException("transient failure"))
      assert(output.toString(UTF_8)=="partial\n**** XSTREAM: ATTEMPT DISCARDED ****\n")
      assert(diagnostics.toString(UTF_8).contains("transient failure"))
      sink.accept(StreamProtocol.start("result","bytes","binary"))
      sink.accept(StreamProtocol.frame("data","result","retry-result".getBytes(UTF_8)))
      sink.onComplete()
      assert(output.toString(UTF_8)=="partial\n**** XSTREAM: ATTEMPT DISCARDED ****\nretry-result")
    }finally {sink.close();errors.close();allocator.close()}
  }

}
