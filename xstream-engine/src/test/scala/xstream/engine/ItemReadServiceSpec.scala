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
}
