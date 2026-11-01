package xstream.engine

import _root_.xstream.execution.{OperatorFrame,StreamCancel}

import _root_.xstream.data._


import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.util.Json

class ComputePushdownSpec extends AnyFunSuite {
  private val a="dacp://node-a:18861";private val b="dacp://node-b:18862"
  private def read(id:String,uri:String)=Task(id,Map.empty,Jobs.ReadItem,new java.util.LinkedHashMap[String,Object](java.util.Map.of("uri",uri)))
  private def process(id:String,up:String*)=Task(id,up.zipWithIndex.map{case(t,i)=>s"in$i"->InputBinding(task=Some(t))}.toMap,"filter@1.0",new java.util.LinkedHashMap[String,Object]())
  private def place(steps:Vector[Task],outputs:Map[String,(String,String)],nodes:Set[String]=Set(a,b),raw:com.fasterxml.jackson.databind.JsonNode=Json.obj())={
    val plan=JobPlan(steps,outputs,"Test");PhysicalPlanner.plan(plan,nodes.toVector.map(n=>new SyntheticTarget {override val nodes=Set(n);override val id=n;def execute(node:String,plan:JobPlan,emit:OperatorFrame=>Unit,cancel:StreamCancel,onExecution:String=>Unit):Unit=()}),ExecutionPolicy.parse(raw,plan))
  }
  test("push a closed remote chain and bind reads to the receiving context") {
    val p=place(Vector(read("read",a+"/weather/file%20name.csv"),process("filter","read")),Map("result"->("filter"->"result")))
    assert(p.regions.size==1 && p.local.steps.map(_.id)==Vector("filter"))
    assert(p.regions.head.plan.steps.head.params.get("uri")=="context:/weather/file%20name.csv")
    assert(Jobs.parse(PhysicalPlanner.yaml(p.regions.head.plan)).outputs==Map("p0"->("filter"->"result")))
    assert(p.local.outputs==Map("result"->("filter"->"p0")))
  }
  test("different remote prefixes execute remotely and converge locally") {
    val steps=Vector(read("a",a+"/d/i"),process("fa","a"),read("b",b+"/d/i"),process("fb","b"),process("join","fa","fb"))
    val p=place(steps,Map("out"->("join"->"result")))
    assert(p.regions.size==2 && p.local.steps.map(_.id)==Vector("fa","fb","join"))
    assert(p.placements("join")=="local")
    assert(p.local.steps.last.inputs.values.forall(_.port=="p0"))
  }
  test("local context, unconfigured nodes and mixed local inputs stay local") {
    val steps=Vector(read("a",a+"/d/i"),read("local","context:/d/i"),process("join","a","local"))
    assert(place(steps,Map("out"->("join"->"result"))).regions.isEmpty)
    assert(place(Vector(read("a",a+"/d/i"),process("f","a")),Map("out"->("f"->"result")),Set.empty).regions.isEmpty)
  }
  test("multiple boundary ports and original Job output names are preserved") {
    val steps=Vector(read("a",a+"/d/i"),process("f","a"),read("a2",a+"/d/j"),process("g","a2"),read("local","context:/d/i"),process("join","f","local"))
    val p=place(steps,Map("joined"->("join"->"result"),"separate"->("g"->"other")))
    assert(p.regions.head.plan.outputs.values.toSet==Set("f"->"result","g"->"other"))
    assert(p.local.outputs.keySet==Set("joined","separate"))
    assert(p.local.steps.last.inputs.values.exists(_.task.contains("g")))
  }
  test("explicit materialization keeps the original policy and graph local") {
    val steps=Vector(read("a",a+"/d/i"),process("f","a"))
    val raw=Json.obj();raw.putObject("materialization").put("default","materialize")
    assert(place(steps,Map("out"->("f"->"result")),raw=raw).regions.isEmpty)
  }
  test("local directory operators and downstream chains stay on the host") {
    val local=process("local","read").copy(operator="",localOperator=Some("./filter"))
    val p=place(Vector(read("read",a+"/d/i"),local,process("next","local")),Map("out"->("next"->"result")))
    assert(p.regions.isEmpty && p.placements("local")=="local" && p.placements("next")=="local")
    intercept[IllegalArgumentException](PhysicalPlanner.yaml(JobPlan(Vector(local),Map.empty)))
  }

}
