package xstream.engine
import org.scalatest.funsuite.AnyFunSuite
class JobDescriptionSpec extends AnyFunSuite {
  private val script="""name: Documented Job
    |steps:
    |  - id: read
    |    operator: read_item@1.0
    |    description: |
    |      Read station observations.
    |      Preserve the source units.
    |    inputs: {}
    |    params: {uri: 'context:/weather/observations', view: records}
    |outputs: {result: read}
    |""".stripMargin
  test("job definitions require steps and reject the superseded collection key") {
    assert(Jobs.parse(script).steps.head.id=="read")
    intercept[IllegalArgumentException](Jobs.parse(script.replace("steps:","tasks:")))
  }
  test("multiline descriptions survive parsing, remote serialization and recorded graphs") {
    val plan=Jobs.parse(script)
    val expected="Read station observations.\nPreserve the source units.\n"
    assert(plan.steps.head.description.contains(expected))
    val serialized=PhysicalPlanner.yaml(plan)
    assert(Jobs.parse(serialized).steps.head.description.contains(expected))
    assert(RecordedJobGraph(serialized).path("steps").get(0).path("description").asText==expected)
  }
  test("descriptions are optional text with an 8192 character bound") {
    val plan=Jobs.parse(script)
    assert(Jobs.parse(script.replace("    description: |\n      Read station observations.\n      Preserve the source units.\n","")).steps.head.description.isEmpty)
    intercept[IllegalArgumentException](Jobs.validate(plan.steps.head.copy(description=Some("x"*8193))))
    intercept[IllegalArgumentException](Jobs.parse(script.replace("description: |\n      Read station observations.\n      Preserve the source units.","description: 123")))
  }
}
