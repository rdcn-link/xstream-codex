package xstream.engine

import xstream.execution._
import xstream.data.Data
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class BytesFileSinkSpec extends AnyFunSuite {
  test("failed attempts preserve the destination and only the successful complete result is published") {
    val dir=Files.createTempDirectory("bytes-file-sink-");val file=dir.resolve("result.csv")
    Files.writeString(file,"previous")
    val sink=new BytesFileSink(file)
    def bytes(value:String):Unit={sink.accept(StreamProtocol.start("result","bytes","csv"));sink.accept(StreamProtocol.frame("data","result",value.getBytes("UTF-8")));sink.accept(StreamProtocol.frame("end","result"))}
    try {
      bytes("failed attempt");assert(Files.readString(file)=="previous")
      sink.onDiscard(new RuntimeException("failed"));assert(Files.readString(file)=="previous")
      bytes("successful attempt");assert(Files.readString(file)=="previous")
      sink.onComplete();assert(Files.readString(file)=="successful attempt")
      sink.close();assert(Files.readString(file)=="successful attempt")
      val files=Files.list(dir);try assert(files.count()==1)finally files.close()
    }finally {sink.close();Data.deleteTree(dir)}
  }
  test("incomplete or records results cannot replace the destination and closing removes staging") {
    val dir=Files.createTempDirectory("bytes-file-sink-");val file=dir.resolve("result")
    try {
      val sink=new BytesFileSink(file)
      try {
        intercept[IllegalArgumentException](sink.accept(StreamProtocol.start("result","records","arrow")))
        sink.accept(StreamProtocol.start("result","bytes","binary"));sink.accept(StreamProtocol.frame("data","result",Array[Byte](1)))
        intercept[IllegalArgumentException](sink.onComplete());assert(!Files.exists(file))
      }finally sink.close()
      val files=Files.list(dir);try assert(files.count()==0)finally files.close()
    }finally Data.deleteTree(dir)
  }
}
