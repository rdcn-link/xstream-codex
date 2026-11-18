package xstream.operator.inference

import org.scalatest.funsuite.AnyFunSuite
import xstream.data.Data
import xstream.execution.{InferenceResult,OperatorCommand,StreamDescriptor}

class OperatorOutputContractsSpec extends AnyFunSuite {
  private val params=Data.mapper.createObjectNode()
  private def operator(view:String):ResolvedOperator={
    val d=Data.mapper.readTree(s"""{"role":"transform","inputs":{"source":{}},"outputs":{"result":{"view":"$view"}}}""")
    ResolvedOperator("test",None,None,OperatorCommand(),Map.empty,
      OperatorOutputContracts.ports(d,params,Map.empty,allowPending=true),declaration=Some(d))
  }
  private def inferred(view:String)=InferenceResult(Map("result"->StreamDescriptor(Set(view))))
  test("fixed Views do not require inference and inference cannot replace declared types or ports"){
    val op=operator("bytes")
    assert(op.outputs("result").representations==Set("bytes"))
    assert(OperatorOutputContracts.validate(op,params,Map.empty,inferred("bytes")).outputs("result").representations==Set("bytes"))
    intercept[IllegalArgumentException](OperatorOutputContracts.validate(op,params,Map.empty,inferred("records")))
    intercept[IllegalArgumentException](OperatorOutputContracts.validate(op,params,Map.empty,InferenceResult(Map("other"->StreamDescriptor(Set("bytes"))))))
    intercept[IllegalArgumentException](OperatorOutputContracts.validate(op,params,Map.empty,InferenceResult(Map.empty)))
  }
  test("concrete inferred structures leave the variant interface declaration intact"){
    val op=operator("variant")
    for(view<-Seq("bytes","records","items","array")){
      val result=OperatorOutputContracts.validate(op,params,Map.empty,inferred(view))
      assert(result.outputs("result").representations==Set(view))
      assert(op.declaration.get.path("outputs").path("result").path("view").asText=="variant")
    }
    intercept[IllegalArgumentException](OperatorOutputContracts.validate(op,params,Map.empty,inferred("variant")))
  }
  test("runtime output contracts accept concrete stream headers and reject unsupported Views"){
    val op=OperatorOutputContracts.bind(operator("variant"),params,Map.empty,availableViews=Set("bytes","records"))
    op.outputs("result").accept(Data.mapper.readTree("""{"representation":"bytes","format":"text"}"""))
    intercept[IllegalArgumentException](op.outputs("result").accept(Data.mapper.readTree("""{"representation":"variant"}""")))
    intercept[IllegalArgumentException](op.outputs("result").accept(Data.mapper.readTree("""{"representation":"unsupported"}""")))
  }
  test("variant inputs accept concrete streams, not declaration markers"){
    val declaration=Data.mapper.readTree("""{"source":{"representations":["variant"],"formats":[]}}""")
    val port=xstream.execution.OperatorPorts.read(declaration,Set("bytes","items"))("source")
    assert(port.representations==Set("bytes","items"))
    port.accept(Data.mapper.readTree("""{"representation":"bytes"}"""))
    port.accept(Data.mapper.readTree("""{"representation":"items","structure":{"view":"items"}}"""))
    for(view<-Seq("variant","variable","records"))intercept[IllegalArgumentException](port.accept(Data.mapper.readTree(s"""{"representation":"$view"}""")))
    intercept[IllegalArgumentException](xstream.execution.OperatorPorts.read(Data.mapper.readTree("""{"source":{"representations":["variable"],"formats":[]}}""")))
    intercept[IllegalArgumentException](OperatorOutputContracts.validate(operator("variant"),params,Map.empty,inferred("variable")))
  }
}
