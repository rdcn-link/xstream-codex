package xstream.data
import org.scalatest.funsuite.AnyFunSuite
class DatasetDescriptionsSpec extends AnyFunSuite {
  test("required title and description preserve the complete original JSON text") {
    val json="""{ "title": "气象观测", "description": "Hourly observations", "owner": {"team":"science"}, "dimensions":["time"] }"""
    assert(DatasetInfo("weather",json).description==json)
    assert(DatasetInfo("ds-2345-6789",json).description==json)
    assert(DatasetInfo("weather").description=="""{"title":"weather","description":""}""")
    DatasetDescriptions.validate(DatasetDefinition("mixed").description)
  }
  test("missing, mistyped, duplicate and malformed required fields are rejected") {
    for(value<-Seq("{}","""{"title":"Weather"}""","""{"description":"Observations"}""",
      """{"title":123,"description":"x"}""","""{"title":"","description":"x"}""",
      """{"title":"Weather","description":null}""","""{"title":"Weather","description":{}}""",
      """{"title":"Weather","title":"Other","description":"x"}""","[]","{} {}")) {
      withClue(value){intercept[IllegalArgumentException](DatasetDescriptions.validate(value))}
    }
  }
}
