package xstream.engine

import _root_.xstream.execution.{OperatorFrame,StreamCancel,StreamProtocol}

import _root_.xstream.data._
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
class LocalEngineSpec extends AnyFunSuite {
  test("read_item selection emits scientific structure and block metadata through a complete Job") {
    val dir=Files.createTempDirectory("xstream-scientific-");Files.writeString(dir.resolve("genome.fa"),">chr1\nACGTACGT\n")
    val settings=Data.mapper.createObjectNode();settings.put("execution_directory",dir.resolve("history").toString)
    settings.putObject("datasets").putObject("files").put("provider","directory").putObject("source").put("root",dir.toString)
    val plugins=new PluginEnvironment();val host=new LocalEngine(settings,plugins)
    val cancel=new StreamCancel()
    try {
      val yaml="name: Scientific read\nsteps:\n- id: read\n  operator: read_item@1.0\n  inputs: {}\n  params: {uri: 'context:/files/genome.fa/sequences', view: sequence, selection: {offset: 2, length: 4}}\noutputs: {result: read}\n"
      val frames=scala.collection.mutable.ArrayBuffer.empty[OperatorFrame]
      host.engine.run(yaml,frames+=_,cancel)
      val start=frames.map(StreamProtocol.header).find(_.path("type").asText=="start").get
      assert(start.path("representation").asText=="sequence" && start.path("structure").path("view").asText=="sequence")
      val data=frames.filter(f=>StreamProtocol.header(f).path("type").asText=="data")
      val allocator=new org.apache.arrow.memory.RootAllocator()
      try {
        val parts=scala.collection.mutable.ArrayBuffer.empty[SequenceChunk]
        data.foreach { frame=>
          val stream=ArrowIpc.readStream(new java.io.ByteArrayInputStream(frame.data),StreamContext(allocator))
          ArrowBlocks.consume(stream,new DataConsumer[SequenceChunk] {
            def start(d:DataDescriptor[SequenceChunk]):Unit=()
            def accept(b:SequenceChunk):Unit=parts+=b
          })
        }
        assert(parts.map(_.symbols.toString).mkString=="GTAC" && parts.head.offset==2)
      }finally allocator.close()

      assert(frames.map(StreamProtocol.header).exists(_.path("type").asText=="end"))
      intercept[IllegalArgumentException](Jobs.parse(yaml.replace("view: sequence","view: records")))
    }finally {cancel.close();host.close();plugins.close();Data.deleteTree(dir)}
  }
  test("standalone context reads share the JVM catalog and stream without a DACP server") {
    val dir=Files.createTempDirectory("xstream-local-");Files.writeString(dir.resolve("input.txt"),"local stream\n")
    val settings=Data.mapper.createObjectNode();settings.put("execution_directory",dir.resolve("history").toString)
    settings.putObject("datasets").putObject("files").put("provider","directory").putObject("source").put("root",dir.toString)
    val plugins=new PluginEnvironment();val host=new LocalEngine(settings,plugins)
    try {
      assert(host.engine.dataCatalog eq DataCatalogRuntime.catalog)
      assert(host.engine.dataCatalog.list().map(_.name)==Seq("files"))
      val yaml="name: Local read\nsteps:\n- id: read\n  operator: read_item@1.0\n  inputs: {}\n  params: {uri: 'context:/files/input.txt', view: bytes}\noutputs: {result: read}\n"
      val out=new java.io.ByteArrayOutputStream();val cancel=new StreamCancel()
      try {host.engine.run(yaml,f=>if(StreamProtocol.header(f).path("type").asText=="data")out.write(f.data),cancel);assert(out.toString("UTF-8")=="local stream\n")}finally cancel.close()
      val other=new DataCatalog {def list():Seq[DatasetInfo]=Seq.empty;def open(n:String):PublishedDataset=throw new UnsupportedOperationException}
      intercept[IllegalArgumentException](DataCatalogRuntime.bind(other))
    }finally {host.close();plugins.close()}
    intercept[IllegalArgumentException](DataCatalogRuntime.catalog)
  }
  test("local execution target exposes result stream, status and completion through the shared contract") {
    val dir=Files.createTempDirectory("local-target-");Files.writeString(dir.resolve("input.txt"),"target stream")
    val settings=Data.mapper.createObjectNode().put("execution_directory",dir.resolve("history").toString)
    settings.putObject("datasets").putObject("files").put("provider","directory").putObject("source").put("root",dir.toString)
    val plugins=new PluginEnvironment();val host=new LocalEngine(settings,plugins);val token=new StreamCancel()
    val yaml="name: Target\nsteps:\n- id: read\n  operator: read_item@1.0\n  inputs: {}\n  params: {uri: 'context:/files/input.txt', view: bytes}\noutputs: {result: read}\n"
    try {
      val target=new LocalExecutionTarget(host.engine)
      val handle=target.submit(_root_.xstream.execution.ExecutionRequest(yaml,_root_.xstream.util.Json.obj(),30),token)
      try {
        val bytes=new java.io.ByteArrayOutputStream();var f=handle.next()
        while(f.nonEmpty){if(StreamProtocol.header(f.get).path("type").asText=="data")bytes.write(f.get.data);f=handle.next()}
        assert(bytes.toString("UTF-8")=="target stream")
        assert(handle.status().state=="succeeded" && handle.status().id.nonEmpty)
      }finally handle.close()
    }finally {token.close();host.close();plugins.close();Data.deleteTree(dir)}
  }
  test("local references are explicit, preserve parameters and reject conflicting declarations") {
    val text="name: Local\nsteps:\n- id: filter\n  operator: {local: ./filter}\n  inputs: {}\n  params: {limit: 123}\noutputs: {result: filter}\n"
    val task=Jobs.parse(text).steps.head
    assert(task.localOperator.contains("./filter") && task.operator.isEmpty && task.source.isEmpty)
    assert(task.params.get("limit")==Integer.valueOf(123))
    intercept[IllegalArgumentException](Jobs.parse(text.replace("local: ./filter","local: ./filter, runtime: python@1.0")))
    intercept[IllegalArgumentException](Jobs.parse(text.replace("local: ./filter","local: 123")))
    intercept[IllegalArgumentException](Jobs.parse(text.replace("local: ./filter","local: ''")))
  }

}
