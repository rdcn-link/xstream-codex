package xstream.engine

import xstream.execution._
import xstream.execution.file.FileCheckpointStore
import xstream.data.Data
import java.nio.file.{Files,Path,StandardOpenOption}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters._

class FileCheckpointContractSpec extends AnyFunSuite {
  private def fixture(f:(FileCheckpointStore,Path)=>Unit):Unit={
    val dir=Files.createTempDirectory("file-checkpoint-");val store=new FileCheckpointStore(dir,1024*1024,512*1024)
    try f(store,dir)finally {store.close();Data.deleteTree(dir)}
  }
  private def frames(port:String)=Vector(StreamProtocol.start(port,"bytes","binary"),StreamProtocol.frame("data",port,Array[Byte](0,1,-1,13,10)),StreamProtocol.frame("end",port))
  test("complete Arrow File checkpoints preserve original frame headers and payloads and protect active readers") {fixture{(store,_)=>
    val writer=store.begin("key",Set("result"),()=>())
    val original=frames("result").map { f=>val h=StreamProtocol.header(f).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode];h.putObject("block").put("index",3);OperatorFrame(Data.mapper.writeValueAsBytes(h),f.data)}
    try {original.foreach(f=>writer.append("result",f));assert(store.get("key").isEmpty);writer.commit()}finally writer.close()
    val data=store.get("key").get
    try {
      intercept[IllegalArgumentException](store.remove("key"))
      val in=data.open("result",()=>());val read=Vector.newBuilder[OperatorFrame]
      try {var f=in.next();while(f.nonEmpty){read+=f.get;f=in.next()}}finally in.close()
      assert(read.result().zip(original).forall{case(a,b)=>a.metadata.sameElements(b.metadata) && a.data.sameElements(b.data)})
    }finally data.close()
    store.remove("key");assert(store.get("key").isEmpty)
  }}
  test("incomplete and poisoned writers cannot commit and abort releases files and quota") {fixture{(store,dir)=>
    val writer=store.begin("incomplete",Set("a","b"),()=>())
    try {frames("a").foreach(f=>writer.append("a",f));intercept[IllegalArgumentException](writer.commit())}finally writer.close()
    assert(store.get("incomplete").isEmpty)
    val bad=store.begin("bad",Set("result"),()=>())
    try {intercept[IllegalArgumentException](bad.append("result",StreamProtocol.frame("data","result")));intercept[IllegalArgumentException](bad.commit())}finally bad.close()
    val files=Files.list(dir);try assert(files.iterator.asScala.map(_.getFileName.toString).toVector==Vector(".writer.lock"))finally files.close()
  }}
  test("corruption is rejected before replay and cancellation interrupts checkpoint reads") {fixture{(store,dir)=>
    val writer=store.begin("key",Set("result"),()=>());try {frames("result").foreach(f=>writer.append("result",f));writer.commit()}finally writer.close()
    val data=store.get("key").get
    try {
      intercept[java.util.concurrent.CancellationException](data.open("result",()=>throw new java.util.concurrent.CancellationException()))
      Files.write(dir.resolve("checkpoint-key/result.arrow"),Array[Byte](42),StandardOpenOption.APPEND)
      intercept[IllegalArgumentException](data.open("result",()=>()))
    }finally data.close()
  }}
  test("quota failures never publish a partial checkpoint and restart clears abandoned execution artifacts") {
    val dir=Files.createTempDirectory("checkpoint-quota-")
    val store=new FileCheckpointStore(dir,128,128)
    try {
      val writer=store.begin("quota",Set("result"),()=>())
      try {intercept[IllegalArgumentException](frames("result").foreach(f=>writer.append("result",f)));intercept[IllegalArgumentException](writer.commit())}finally try writer.close()catch{case _:IllegalArgumentException=>()}
      assert(store.get("quota").isEmpty)
    }finally store.close()
    Files.createDirectory(dir.resolve("checkpoint-stale"));Files.writeString(dir.resolve("checkpoint-stale/data"),"stale")
    val restarted=new FileCheckpointStore(dir,1024,1024)
    try assert(!Files.exists(dir.resolve("checkpoint-stale")))finally {restarted.close();Data.deleteTree(dir)}
  }
}
