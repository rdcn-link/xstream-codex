package dacp

import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.data.{Data,PluginEnvironment}

class LocalOperatorInferenceSpec extends AnyFunSuite {
  test("local Python and Scala use the SDK inference and bounded sample protocol") {
    assume(sys.env.get("XSTREAM_LOCAL_OPERATOR_TEST").contains("1"),"set XSTREAM_LOCAL_OPERATOR_TEST=1 with Docker and example runtime images")
    val example=Path.of("../examples/operators/local").toAbsolutePath.normalize
    val directory=Files.createTempDirectory("local-inference-")
    val settings=Data.mapper.createObjectNode().put("execution_directory",directory.resolve("history").toString)
      .put("operator_cache",directory.resolve("cache").toString).put("job_working_directory",example.toString)
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
