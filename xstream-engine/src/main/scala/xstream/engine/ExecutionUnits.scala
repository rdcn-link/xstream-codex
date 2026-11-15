package xstream.engine

import _root_.xstream.execution.{PortContract}

import _root_.xstream.operator.inference._
import _root_.xstream.data._
import _root_.xstream.util.Json
import scala.jdk.CollectionConverters._
import com.fasterxml.jackson.databind.JsonNode

case class ExecutionUnits(plan:JobPlan,groups:Map[String,PreparedOperatorGroup],description:JsonNode)
object ExecutionUnits {
  /** Topological runs are convex: collapsing one cannot create a dependency cycle. */
  def regions(plan:JobPlan,keys:Map[String,String]):Vector[Vector[String]]={
    var remaining=plan.steps;var done=Set.empty[String];var ordered=Vector.empty[Task]
    while(remaining.nonEmpty){
      val ready=remaining.filter(t=>Jobs.dependencies(t).forall(done));require(ready.nonEmpty,"cyclic execution graph")
      val t=ready.head;ordered :+= t;done+=t.id;remaining=remaining.filterNot(_.id==t.id)
    }
    var runs=Vector.empty[Vector[String]]
    ordered.foreach { t=>
      if(runs.nonEmpty && keys.contains(t.id) && runs.last.forall(id=>keys.get(id)==keys.get(t.id)) && Jobs.dependencies(t).exists(runs.last.contains))
        runs=runs.init :+ (runs.last :+ t.id)
      else runs :+= Vector(t.id)
    };runs.filter(_.size>1)
  }
  def prepare(config:EngineEnvironment,plan:JobPlan,check:()=>Unit=()=>()):ExecutionUnits={
    if(config.operatorFusion=="isolated" || plan.steps.size<2)return ExecutionUnits(plan,Map.empty,Json.obj())
    OperatorInference.withDocker { docker=>
      val operators=plan.steps.filterNot(_.operator==Jobs.ItemSource).map { t=>
        check();val op=t.preparedInline.getOrElse(t.localOperator.map(r=>LocalOperatorLibrary.resolve(config,config.localOperatorBase.get,r,docker,check)).getOrElse(OperatorLibrary.resolve(config,t.operator,docker)))
        t.id->op
      }.toMap
      val declared=OperatorGroups.resolveDeclared(plan,operators)
      val keys=declared.filter(_._2.outputs.nonEmpty).map{case(id,op)=>id->(op.executor+":"+OperatorLibrary.runtimeDescriptor(op).path("dependency_environment").asText+":"+op.command.environment.toVector.sorted.mkString)}
      var routing=Map.empty[(String,String),(String,String)]
      var removed=Set.empty[String];var groups=Map.empty[String,PreparedOperatorGroup];var replacements=Map.empty[String,Task]
      val description=Json.obj().put("operator_fusion",config.operatorFusion);val units=description.putArray("execution_units")
      val decisions=description.putArray("fusion_decisions")
      regions(plan,keys).foreach { ids=>
        val selected=ids.toSet;val members=plan.steps.filter(t=>selected(t.id))
        val external=members.flatMap(t=>t.inputs.toVector.sortBy(_._1).collect{case(p,b)if b.task.forall(id=> !selected(id))=>(t.id,p,b)})
        val ports=(plan.steps.filterNot(t=>selected(t.id)).flatMap(_.inputs.values.flatMap(b=>b.task.filter(selected).map(_->b.port)))++plan.outputs.values.filter(p=>selected(p._1))).distinct.sortBy(p=>p._1+":"+p._2)
        val sub=plan.copy(steps=members.map(t=>t.copy(inputs=t.inputs.map{case(p,b)=>p->(if(b.task.exists(id=> !selected(id)))InputBinding(uri=Some("unit:"+b.task.get+":"+b.port),view=b.view)else b)})),outputs=ports.zipWithIndex.map{case(p,i)=>s"export$i"->p}.toMap)
        val selection=FusionPolicy.decide(config.operatorFusion,sub)
        val detail=decisions.addObject();detail.set[JsonNode]("estimate",selection.json);val candidates=detail.putArray("steps");ids.foreach(candidates.add)
        if(ports.nonEmpty)OperatorGroups.prepareResolved(config,sub,declared.filter(p=>selected(p._1))) match {
          case Right(group)=>
            val id="unit-"+ids.head
            val bindings=external.zipWithIndex.map{case((_,_,b),i)=>s"i$i"->b}.toMap
            val t=group.task.copy(id=id,inputs=bindings)
            val g=group.copy(task=t,inputs=bindings)
            groups+=id->g;replacements+=ids.head->t;removed++=selected
            val labelToPort=group.outputs.map{case(port,(_,label))=>label->port}
            sub.outputs.foreach{case(label,source)=>routing+=source->(id,labelToPort(label))}
            detail.put("result","shared_process")
            val u=units.addObject().put("id",id).put("mode","shared_process").put("reason",selection.reason)
            OperatorLibrary.runtimeDescriptor(group.operator).fields.asScala.foreach(e=>u.set[JsonNode](e.getKey,e.getValue))
            val a=u.putArray("steps");ids.foreach(a.add)
          case Left(reason)=>detail.put("result","isolated_steps").put("reason",reason)
        }
      }
      if(groups.isEmpty)ExecutionUnits(plan,Map.empty,description)else {
        def bind(b:InputBinding):InputBinding=b.task.flatMap(t=>routing.get(t->b.port)).map{case(t,p)=>b.copy(task=Some(t),port=p)}.getOrElse(b)
        val steps=plan.steps.flatMap(t=>replacements.get(t.id).orElse(if(removed(t.id))None else Some(t))).map(t=>t.copy(inputs=t.inputs.view.mapValues(bind).toMap))
        plan.steps.filterNot(t=>removed(t.id)).foreach { t=>
          val u=units.addObject().put("id",t.id).put("mode",if(t.operator==Jobs.ItemSource)"host_stream_source"else "isolated_step").put("reason",if(t.operator==Jobs.ItemSource)"host_catalog_access"else "dependency_or_executor_boundary")
          u.putArray("steps").add(t.id);operators.get(t.id).foreach(op=>OperatorLibrary.runtimeDescriptor(op).fields.asScala.foreach(e=>u.set[JsonNode](e.getKey,e.getValue)))
        }
        ExecutionUnits(plan.copy(steps=steps,outputs=plan.outputs.view.mapValues(p=>routing.getOrElse(p,p)).toMap),groups,description)
      }
    }
  }
}
