package xstream.examples.endpoints

import java.io.{ByteArrayInputStream,ByteArrayOutputStream}
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import xstream.execution.{OperatorFrames,StreamProtocol}
import xstream.operator.OperatorRunner
import xstream.data.PluginSchemas

class EndpointsSpec extends AnyFunSuite {
  private def withInput(chunks:Seq[String])(run:(java.nio.file.Path,java.nio.file.Path)=>Unit):Unit={
    val dir=Files.createTempDirectory("endpoint-input-");val allocator=new org.apache.arrow.memory.RootAllocator()
    val root=org.apache.arrow.vector.VectorSchemaRoot.create(PluginSchemas.bytes,allocator)
    try {
      Files.writeString(dir.resolve("manifest.json"),"""[{"port":"source","file":"input.arrow","representation":"bytes","format":"text"}]""")
      val raw=Files.newOutputStream(dir.resolve("input.arrow"));val writer=new org.apache.arrow.vector.ipc.ArrowStreamWriter(root,null,raw)
      try {
        writer.start()
        chunks.foreach { text=>root.clear();root.allocateNew();root.getVector(0).asInstanceOf[org.apache.arrow.vector.VarBinaryVector].setSafe(0,text.getBytes("UTF-8"));root.setRowCount(1);writer.writeBatch() }
        writer.end()
      } finally {writer.close();raw.close()}
      run(dir,dir.resolve("params.json"))
    } finally {root.close();allocator.close();Seq("manifest.json","input.arrow","params.json").foreach(n=>Files.deleteIfExists(dir.resolve(n)));Files.delete(dir)}
  }
  test("Source operators emit bounded bytes without inputs") {
    val dir=Files.createTempDirectory("endpoint-source-")
    try {
      Files.writeString(dir.resolve("manifest.json"),"[]")
      def run(params:String,process:xstream.operator.OperatorContext=>Unit):String={
        Files.writeString(dir.resolve("params.json"),params);val out=new ByteArrayOutputStream()
        OperatorRunner.run(Array("operator",dir.toString,dir.resolve("params.json").toString),out)(process)
        val in=new ByteArrayInputStream(out.toByteArray)
        Iterator.continually(OperatorFrames.read(in)).takeWhile(_.nonEmpty).map(_.get).filter(f=>StreamProtocol.header(f).path("type").asText=="data").map(f=>new String(f.data,"UTF-8")).mkString
      }
      assert(run("""{"text":"ok","repeat":3}""",TextSource.process)=="okokok")
      assert(run("""{"start":-1,"count":3}""",RangeSource.process)=="-1\n0\n1\n")
      intercept[ArithmeticException](RangeSource.inferOutputs(Map.empty,xstream.data.Data.mapper.readTree("""{"start":9223372036854775807,"count":2}""")))
    }finally {Seq("manifest.json","params.json").foreach(n=>Files.deleteIfExists(dir.resolve(n)));Files.delete(dir)}
  }
  test("Sink operators validate complete streams across Arrow chunk boundaries") {
    withInput(Seq("abc","def\ng")){(dir,params)=>
      Files.writeString(params,"""{"text":"cde"}""")
      OperatorRunner.run(Array("operator",dir.toString,params.toString),new ByteArrayOutputStream())(ContainsTextSink.process)
      Files.writeString(params,"""{"text":"missing"}""")
      intercept[IllegalArgumentException](OperatorRunner.run(Array("operator",dir.toString,params.toString),new ByteArrayOutputStream())(ContainsTextSink.process))
      Files.writeString(params,"""{"minimum":2}""")
      OperatorRunner.run(Array("operator",dir.toString,params.toString),new ByteArrayOutputStream())(MinLinesSink.process)
      Files.writeString(params,"""{"minimum":3}""")
      intercept[IllegalArgumentException](OperatorRunner.run(Array("operator",dir.toString,params.toString),new ByteArrayOutputStream())(MinLinesSink.process))
    }
  }
}
