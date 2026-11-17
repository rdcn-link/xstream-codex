package xstream.engine

import xstream.data._
import xstream.execution._
import org.apache.arrow.memory.RootAllocator
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files

class ItemReadServiceSpec extends AnyFunSuite {
  test("lazy Item opening delegates authorization, streams bytes and closes each lease") {
    val dir=Files.createTempDirectory("item-ipc-");val allocator=new RootAllocator();val cancel=new StreamCancel()
    var opened=0;var closed=0
    val service=new ItemReadService(dir,allocator,cancel,(binding,token)=>{
      opened+=1;require(binding.uri.contains("context:/science/a.txt"),"FORBIDDEN: denied Item")
      val frames=Iterator(StreamProtocol.start("","bytes","text"),StreamProtocol.frame("data",data="hello".getBytes("UTF-8")),StreamProtocol.frame("end"))
      new FrameSource {def next()={token.active();if(frames.hasNext)Some(frames.next())else None};def close():Unit={closed+=1}}
    })
    try {
      val lazyStream=ItemReadProtocol.open(dir.resolve(ItemReadProtocol.SocketName),"context:/science/a.txt","bytes",null,allocator)
      assert(opened==0)
      val bytes=new java.io.ByteArrayOutputStream()
      ArrowBlocks.consume(lazyStream,new DataConsumer[ByteChunk]{def start(d:DataDescriptor[ByteChunk]):Unit=();def accept(b:ByteChunk):Unit=bytes.write(b.bytes,b.offset,b.length)})
      assert(opened==1 && bytes.toString("UTF-8")=="hello")
      intercept[IllegalArgumentException](ItemReferences.foreach(ItemReadProtocol.open(dir.resolve(ItemReadProtocol.SocketName),"context:/science/denied.txt","items",null,allocator))(_=>()))
      intercept[IllegalArgumentException](ItemReadProtocol.open(dir.resolve(ItemReadProtocol.SocketName),"context:/science/%2e%2e/private","bytes",null,allocator))
    }finally {service.close();cancel.close();allocator.close();Data.deleteTree(dir)}
    assert(closed>=1)
  }
  test("mid-stream failure cannot become successful Arrow EOS and cancellation removes the private socket") {
    val dir=Files.createTempDirectory("item-ipc-failure-");val allocator=new RootAllocator();val cancel=new StreamCancel()
    val service=cancel.add(new ItemReadService(dir,allocator,cancel,(_,_)=>new FrameSource {
      var first=true
      def next():Option[OperatorFrame]=if(first){first=false;Some(StreamProtocol.start("","bytes","text"))}else throw new IllegalArgumentException("failed after header")
      def close():Unit=()
    }))
    try {
      intercept[Exception](ArrowBlocks.consume(ItemReadProtocol.open(dir.resolve(ItemReadProtocol.SocketName),"context:/science/a","bytes",null,allocator),new DataConsumer[ByteChunk]{def start(d:DataDescriptor[ByteChunk]):Unit=();def accept(b:ByteChunk):Unit=()}))
      cancel.close();assert(!Files.exists(dir.resolve(ItemReadProtocol.SocketName)))
    }finally {service.close();cancel.close();allocator.close();Data.deleteTree(dir)}
  }
  test("successful completion requires the Item source lease to close successfully") {
    val dir=Files.createTempDirectory("item-ipc-close-");val allocator=new RootAllocator();val cancel=new StreamCancel()
    val service=new ItemReadService(dir,allocator,cancel,(_,_)=>new FrameSource {
      val frames=Iterator(StreamProtocol.start("","bytes","binary"),StreamProtocol.frame("data",data=Array[Byte](0,1,2)),StreamProtocol.frame("end"))
      def next():Option[OperatorFrame]=if(frames.hasNext)Some(frames.next())else None
      def close():Unit=throw new IllegalStateException("Item lease release failed")
    })
    try {
      intercept[Exception](ArrowBlocks.consume(ItemReadProtocol.open(dir.resolve(ItemReadProtocol.SocketName),"context:/science/a","bytes",null,allocator),new DataConsumer[ByteChunk]{def start(d:DataDescriptor[ByteChunk]):Unit=();def accept(b:ByteChunk):Unit=()}))
    }finally {service.close();cancel.close();allocator.close();Data.deleteTree(dir)}
  }
  test("Python SDK consumes repeated empty and multi-batch byte Views through the live Item socket") {
    val python=sys.env.getOrElse("XSTREAM_TEST_PYTHON","python3")
    val probe=new ProcessBuilder(python,"-c","import pyarrow").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    if(!probe.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){probe.destroyForcibly();fail("Python probe timed out")}
    if(probe.exitValue()!=0)cancel("Set XSTREAM_TEST_PYTHON to a Python interpreter with PyArrow")
    val dir=Files.createTempDirectory("item-ipc-python-");val allocator=new RootAllocator();val cancellation=new StreamCancel()
    val chunk=Array.tabulate[Byte](4096)(i=>(i%251).toByte)
    val service=new ItemReadService(dir,allocator,cancellation,(binding,token)=>{
      val body=if(binding.uri.get.endsWith("/empty"))Iterator.empty else Iterator.fill(64)(StreamProtocol.frame("data",data=chunk))
      val frames=Iterator(StreamProtocol.start("","bytes","binary"))++body++Iterator(StreamProtocol.frame("end"))
      new FrameSource {def next():Option[OperatorFrame]={token.active();if(frames.hasNext)Some(frames.next())else None};def close():Unit=()}
    })
    val script="""
import sys
from pathlib import Path
from xstream_operator import OperatorContext
root=Path(sys.argv[1])
ctx=OperatorContext(['operator', str(root), str(root/'params.json')])
expected=bytes(i % 251 for i in range(4096))*64
for iteration in range(100):
    for name,payload in [('empty',b''),('payload',expected)]:
        with ctx.open_item('context:/science/'+name,'bytes') as view:
            assert b''.join(view.bytes())==payload, (iteration,name)
print('PASS 200 live Item reads, Arrow EOS and completion messages')
"""
    try {
      Files.writeString(dir.resolve("manifest.json"),"[]");Files.writeString(dir.resolve("params.json"),"{}")
      val log=dir.resolve("python.log")
      val builder=new ProcessBuilder(python,"-c",script,dir.toString).redirectErrorStream(true).redirectOutput(log.toFile)
      builder.environment().put("PYTHONPATH",java.nio.file.Path.of("../xstream-operator-sdk/python").toAbsolutePath.normalize.toString)
      val process=builder.start()
      if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("Python Item reads timed out: "+Files.readString(log))}
      assert(process.exitValue()==0,Files.readString(log))
    }finally {service.close();cancellation.close();allocator.close();Data.deleteTree(dir)}
  }

}
