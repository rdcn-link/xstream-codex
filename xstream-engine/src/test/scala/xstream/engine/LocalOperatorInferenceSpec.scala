package xstream.engine

import _root_.xstream.execution.{InferenceRequest,OutputSample,PreviewLimits,StreamCancel,StreamDescriptor}

import java.nio.file.{Files,Path}
import scala.jdk.CollectionConverters._
import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.data.{Data,PluginEnvironment}

class LocalOperatorInferenceSpec extends AnyFunSuite {
  test("local Python and Scala use the SDK inference and bounded sample protocol") {
    assume(sys.env.get("XSTREAM_LOCAL_OPERATOR_TEST").contains("1"),"set XSTREAM_LOCAL_OPERATOR_TEST=1 with Docker and example runtime images")
    val example=Path.of("../examples/operators/local").toAbsolutePath.normalize
    val directory=Files.createTempDirectory("local-inference-")
    val fixture=directory.resolve("operators")
    val paths=Files.walk(example)
    try paths.forEach { source =>
      val relative=example.relativize(source)
      if(!relative.iterator().asScala.exists(part=>Set("target","__pycache__").contains(part.toString))) {
        val target=fixture.resolve(relative)
        if(Files.isDirectory(source))Files.createDirectories(target)
        else Files.copy(source,target)
      }
    }finally paths.close()
    sys.env.get("XSTREAM_TEST_JVM_IMAGE").foreach { image =>
      val manifest=fixture.resolve("scala/local.yaml")
      require(image.matches("[A-Za-z0-9][A-Za-z0-9./:@_-]*"),"invalid test JVM image")
      val lines=Files.readAllLines(manifest).asScala.map(line=>if(line.startsWith("image:"))s"image: $image"else line)
      Files.writeString(manifest,lines.mkString("","\n","\n"))
    }
    val settings=Data.mapper.createObjectNode().put("execution_directory",directory.resolve("history").toString)
      .put("operator_cache",directory.resolve("cache").toString).put("job_working_directory",fixture.toString)
    val registry=new PluginEnvironment();val host=new LocalEngine(settings,registry);val cancel=new StreamCancel()
    try {
      val sample=OutputSample("bytes","text",data="abc".getBytes("UTF-8").toVector)
      val source=StreamDescriptor(Set("bytes"),Set("text"),certainty="known",sample=Some(sample))
      for(reference<-Seq("./python","./scala")){
        val result=host.inferLocal(reference,InferenceRequest(Map("source"->source),Data.mapper.createObjectNode(),Some(PreviewLimits(2,256))),cancel)
        assert(result.outputs("result").representations==Set("bytes"))
        val data=result.outputs("result").sample.get.data.toArray
        assert(new String(data,"UTF-8")==(if(reference.endsWith("python"))"ABC"else "abc"))
      }
    }finally {cancel.close();host.close();registry.close();Data.deleteTree(directory)}
  }
}
