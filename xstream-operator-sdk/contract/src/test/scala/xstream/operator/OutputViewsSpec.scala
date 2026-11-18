package xstream.operator

import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.funsuite.AnyFunSuite

class OutputViewsSpec extends AnyFunSuite {
  private val json=new ObjectMapper
  private val empty=json.readTree("{}")
  private def definition(output:String,role:String="transform")=json.readTree(s"""{"role":"$role","inputs":{"source":{}},"outputs":{"result":$output},"parameters":{"properties":{"view":{"type":"string","enum":["bytes","records"]}}}}""")
  test("a fixed output has exactly one declared View") {
    val d=definition("""{"view":"records"}""")
    assert(OutputViews.resolve(d,empty,Map.empty)==Map("result"->Some("records")))
    Seq("null","[]","{}","{\"view\":[\"bytes\",\"records\"]}","{\"view\":\"auto\"}","{\"view\":{\"parameter\":\"view\"}}").foreach(v=>intercept[IllegalArgumentException](OutputViews.validate(definition(v))))
  }
  test("a variant declaration stays variant when concrete invocation Views resolve") {
    val d=definition("""{"view":"variant","view_from":{"parameter":"view"}}""")
    Seq("bytes","records").foreach { view=>
      assert(OutputViews.resolve(d,json.readTree(s"""{"view":"$view"}"""),Map.empty)==Map("result"->Some(view)))
      assert(d.path("outputs").path("result").path("view").asText=="variant")
    }
    assert(OutputViews.resolve(d,empty,Map.empty)==Map("result"->None))
    val input=definition("""{"view":"variant","view_from":{"input":"source"}}""")
    assert(OutputViews.resolve(input,empty,Map("source"->Set("items")))==Map("result"->Some("items")))
    assert(input.path("outputs").path("result").path("view").asText=="variant")
    assert(OutputViews.resolve(definition("""{"view":"variant"}"""),empty,Map.empty)==Map("result"->None))
  }
  test("invalid selectors and Sink outputs are rejected") {
    Seq("{\"parameter\":\"missing\"}","{\"input\":\"missing\"}","{\"input\":\"source\",\"parameter\":\"view\"}","null").foreach { selector=>
      intercept[IllegalArgumentException](OutputViews.validate(definition(s"""{"view":"variant","view_from":$selector}""")))
    }
    intercept[IllegalArgumentException](OutputViews.validate(definition("""{"view":"bytes","view_from":{"input":"source"}}""")))
    intercept[IllegalArgumentException](OutputViews.validate(json.readTree("{\"role\":\"source\",\"inputs\":{}}")))
    intercept[IllegalArgumentException](OutputViews.validate(definition("{\"view\":\"bytes\"}","sink")))
    OutputViews.validate(json.readTree("{\"role\":\"sink\",\"inputs\":{\"source\":{}},\"outputs\":{}}"))
  }
  test("variant is declaration-only and concrete plugin View names remain valid") {
    assert(OutputViews.isDeclared("variant"))
    assert(!OutputViews.isConcrete("variant"))
    assert(!OutputViews.isDeclared("variable"))
    assert(OutputViews.isConcrete("scientific_tensor"))
    intercept[IllegalArgumentException](OutputViews.validate(definition("""{"view":"variable"}""")))
    assert(OutputViews.resolve(definition("""{"view":"scientific_tensor"}"""),empty,Map.empty)==Map("result"->Some("scientific_tensor")))
    val d=definition("""{"view":"variant","view_from":{"parameter":"view"}}""")
    for(value<-Seq("variant","variable","auto","$VIEW"))assert(OutputViews.resolve(d,json.readTree(s"""{"view":"$value"}"""),Map.empty)==Map("result"->None))
  }
}
