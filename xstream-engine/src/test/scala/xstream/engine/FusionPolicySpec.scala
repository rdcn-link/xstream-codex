package xstream.engine

import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.util.Json

class FusionPolicySpec extends AnyFunSuite {
  private def step(id:String,parents:String*)=Task(id,parents.zipWithIndex.map{case(p,i)=>s"p$i"->InputBinding(task=Some(p))}.toMap,"copy@1",new java.util.LinkedHashMap[String,Object]())
  private val chain=JobPlan(Vector(step("a"),step("b","a"),step("c","b")),Map("result"->("c","result")))
  test("strict modes and conservative automatic default") {
    assert(FusionPolicy.parse(None)=="auto")
    FusionPolicy.modes.foreach(m=>assert(FusionPolicy.parse(Some(Json.mapper.getNodeFactory.textNode(m)))==m))
    Seq("true","null","42","\"enabled\"").foreach(raw=>intercept[IllegalArgumentException](FusionPolicy.parse(Some(Json.mapper.readTree(raw)))))
    val plugins=new xstream.data.PluginEnvironment()
    try intercept[IllegalArgumentException](new LocalEngine(Json.obj().put("operator_fusion","invalid"),plugins)) finally plugins.close()
  }
  test("chain saves startup and transport proxy costs; isolated overrides estimate") {
    val d=FusionPolicy.decide("auto",chain)
    assert(d.allowed && d.savedCost==202 && d.parallelismCost==0)
    assert(!FusionPolicy.decide("isolated",chain).allowed)
    assert(d.json.path("confidence").asText=="low")
  }
  test("automatic selection preserves branch parallelism; fused permits compatible candidates") {
    val join=chain.copy(steps=Vector(step("a"),step("b"),step("c","a","b")))
    val fanout=chain.copy(steps=Vector(step("a"),step("b","a"),step("c","a")))
    for(p<-Seq(join,fanout)){
      val d=FusionPolicy.decide("auto",p)
      assert(!d.allowed && d.parallelismCost>d.savedCost)
      assert(FusionPolicy.decide("fused",p).allowed)
    }
    val externalJoin=chain.copy(steps=chain.steps.init:+chain.steps.last.copy(inputs=Map("a"->InputBinding(task=Some("b")),"b"->InputBinding(uri=Some("context:/ds/other")))))
    assert(!FusionPolicy.decide("auto",externalJoin).allowed)
  }
  test("independent and single steps never win automatic fusion") {
    assert(!FusionPolicy.decide("auto",chain.copy(steps=Vector(step("a"),step("b")))).allowed)
    assert(!FusionPolicy.decide("auto",chain.copy(steps=Vector(step("a")))).allowed)
  }
}
