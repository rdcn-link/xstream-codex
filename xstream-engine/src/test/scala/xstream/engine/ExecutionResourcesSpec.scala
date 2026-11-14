package xstream.engine
import xstream.util.Json
import org.scalatest.funsuite.AnyFunSuite
class ExecutionResourcesSpec extends AnyFunSuite {
  private val plan=Jobs.parse("""name: reader
steps:
- id: read
  operator: item_source@1.0
  inputs: {}
  params: {uri: 'context:/data/input', view: bytes}
- id: client_sink
  operator: client_sink@1.0
  inputs: {result: read}
""")
  test("resource requests survive effective policy serialization with explicit defaults") {
    val raw=Json.mapper.readTree("""{"resources":{"tmp_size_mb":2048,"memory_mb":4096}}""")
    val p=ExecutionPolicy.parse(raw,plan)
    assert(p.resources==ExecutionResources(2048,4096))
    assert(ExecutionPolicy.parse(p.effective,plan).resources==p.resources)
    assert(p.submitted==raw)
    assert(ExecutionPolicy.parse(Json.obj(),plan).resources==ExecutionResources())
  }
  test("invalid requests and requests exceeding host limits fail closed") {
    Seq("null","[]","{\"tmp_size_mb\":0}","{\"tmp_size_mb\":1.5}","{\"tmp_size_mb\":\"512\"}","{\"tmp_size_mb\":1024}","{\"memory_mb\":64}","{\"path\":\"/host\"}").foreach { raw=>
      intercept[IllegalArgumentException](ExecutionResources.parse(Json.mapper.readTree(raw)))
    }
    val limits=ExecutionResourceLimits(1024,2048)
    limits.validate(ExecutionResources(768,2048))
    intercept[IllegalArgumentException](limits.validate(ExecutionResources(1536,2048)))
    intercept[IllegalArgumentException](limits.validate(ExecutionResources(512,4096)))
    intercept[IllegalArgumentException](ExecutionResourceLimits.parse(Json.mapper.readTree("""{"max_tmp_size_mb":0}""")))
  }
}
