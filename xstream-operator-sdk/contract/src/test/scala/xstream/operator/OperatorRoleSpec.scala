package xstream.operator

import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.funsuite.AnyFunSuite

class OperatorRoleSpec extends AnyFunSuite {
  private val mapper=new ObjectMapper()
  test("role is explicit and independent of executor type") {
    for(role<-Seq("source","transform","sink"))assert(OperatorRole.read(mapper.readTree(s"""{"role":"$role","type":"sdk"}""")).id==role)
    for(json<-Seq("{}","{\"role\":\"unknown\"}","{\"role\":42}"))intercept[IllegalArgumentException](OperatorRole.read(mapper.readTree(json)))
  }
  test("source, transform and sink validate port direction") {
    val none=mapper.readTree("{}")
    val one=mapper.readTree("""{"source":{}}""")
    OperatorRole.validateInputs(OperatorRole.Source,none)
    OperatorRole.validateInputs(OperatorRole.Transform,one)
    OperatorRole.validateInputs(OperatorRole.Sink,one)
    OperatorRole.validateOutputs(OperatorRole.Source,one)
    OperatorRole.validateOutputs(OperatorRole.Transform,one)
    OperatorRole.validateOutputs(OperatorRole.Sink,none)
    intercept[IllegalArgumentException](OperatorRole.validateInputs(OperatorRole.Source,one))
    intercept[IllegalArgumentException](OperatorRole.validateInputs(OperatorRole.Transform,none))
    intercept[IllegalArgumentException](OperatorRole.validateOutputs(OperatorRole.Sink,one))
  }
}
