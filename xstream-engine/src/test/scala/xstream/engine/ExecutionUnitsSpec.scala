package xstream.engine
import org.scalatest.funsuite.AnyFunSuite
class ExecutionUnitsSpec extends AnyFunSuite {
  def task(id:String,up:Option[String]=None)=Task(id,up.map(u=>Map("source"->InputBinding(task=Some(u)))).getOrElse(Map.empty),"test@1",new java.util.LinkedHashMap[String,Object]())
  test("same-environment connected runs fuse while independent branches and conflicts remain separate") {
    val plan=JobPlan(Vector(task("a"),task("b",Some("a")),task("c",Some("b")),task("d",Some("c"))),Map("result"->("d","result")))
    assert(ExecutionUnits.regions(plan,Map("a"->"one","b"->"two","c"->"two","d"->"three"))==Vector(Vector("b","c")))
    assert(ExecutionUnits.regions(plan.copy(steps=Vector(task("a"),task("b"))),Map("a"->"same","b"->"same")).isEmpty)
  }
  test("topological ordering prevents cyclic collapsed regions") {
    val p=JobPlan(Vector(task("c",Some("b")),task("a"),task("b",Some("a"))),Map("result"->("c","result")))
    assert(ExecutionUnits.regions(p,Map("a"->"same","b"->"other","c"->"same")).isEmpty)
  }
}
