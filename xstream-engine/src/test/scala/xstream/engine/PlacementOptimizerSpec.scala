package xstream.engine

import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.execution._
import _root_.xstream.util.Json
class PlacementOptimizerSpec extends AnyFunSuite {
  test("latency and transfer objectives can select different physical locations") {
    val choices=Seq(PlacementCandidate("local",1000),PlacementCandidate("remote",10,1000))
    assert(PlacementOptimizer.choose(choices,"latency").target=="local")
    assert(PlacementOptimizer.choose(choices,"transfer").target=="remote")
  }
  test("local policy does not contact remote targets") {
    val target=new SyntheticTarget {override def describe(c:StreamCancel):TargetDescription=throw new AssertionError("must not probe");def execute(n:String,p:JobPlan,e:OperatorFrame=>Unit,c:StreamCancel,on:String=>Unit):Unit=()}
    val p=JobPlan(Vector.empty,Map.empty);val c=new StreamCancel()
    try assert(PhysicalPlanner.select(p,Seq(target),ExecutionPolicy.parse(Json.obj(),p),c).regions.isEmpty)finally c.close()
  }
  test("a data-readable target without job-submit permission never receives a region") {
    val target=new SyntheticTarget {override def describe(c:StreamCancel)=TargetDescription(id,false);def execute(n:String,p:JobPlan,e:OperatorFrame=>Unit,c:StreamCancel,on:String=>Unit):Unit=throw new AssertionError("unauthorized submit")}
    val p=Jobs.parse("name: Read\nsteps:\n- id: read\n  operator: read_item@1.0\n  params: {uri: 'dacp://remote:18861/d/i'}\n  inputs: {}\noutputs: {result: read}\n")
    val policy=Json.obj();policy.putObject("placement").put("mode","auto")
    val c=new StreamCancel();try assert(PhysicalPlanner.select(p,Seq(target),ExecutionPolicy.parse(policy,p),c).regions.isEmpty)finally c.close()
  }
  test("graph placement uses host-supplied data ownership and capacities without protocol assumptions") {
    val steps=Vector(PlanningStep("read",Vector.empty,Some("east"->Some(100L))),PlanningStep("filter",Vector("read")),PlanningStep("sink",Vector("filter")))
    val result=GraphPlacement.plan(steps,Vector(PlanningTarget("east",2),PlanningTarget("west",2)))
    assert(result("read").target=="east" && result("filter").target=="east" && result("sink").target=="west")
    assert(result("read").reason=="data_locality")
    intercept[IllegalArgumentException](GraphPlacement.plan(steps,Vector(PlanningTarget("west",10))))
  }
  test("placement policy rejects malformed modes and targets") {
    val p=JobPlan(Vector.empty,Map.empty)
    for(text<-Seq("{placement: {mode: remote}}","{placement: {targets: [2]}}","{placement: {objective: fastest}}"))
      intercept[IllegalArgumentException](ExecutionPolicy.parse(_root_.xstream.util.YamlDocument.parse(text),p))
  }
}
