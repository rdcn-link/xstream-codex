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
    if(!config.operatorFusionEnabled || plan.steps.size<2 || plan.steps.exists(_.source.nonEmpty))return ExecutionUnits(plan,Map.empty,Json.obj())
    OperatorInference.withDocker { docker=>
      val operators=plan.steps.filterNot(_.operator==Jobs.ReadItem).map { t=>
        check();val op=t.localOperator.map(r=>LocalOperatorLibrary.resolve(config,config.localOperatorBase.get,r,docker,check)).getOrElse(OperatorLibrary.resolve(config,t.operator,docker))
        val ports=plan.steps.flatMap(_.inputs.values.collect{case b if b.task.contains(t.id)=>b.port}).toSet++plan.outputs.values.collect{case(id,p)if id==t.id=>p}
        val outs=ports.map(_->PortContract(Set("bytes","records"),Set.empty,None)).toMap
        t.id->op.copy(outputs=outs,program=op.program.map(_.copy(outputs=outs.view.mapValues(_.representations).toMap)))
      }.toMap
      val keys=operators.filter(_._2.outputs.nonEmpty).map{case(id,op)=>id->(op.executor+":"+OperatorLibrary.runtimeDescriptor(op).path("dependency_environment").asText+":"+op.command.environment.toVector.sorted.mkString)}
      var routing=Map.empty[(String,String),(String,String)]
      var removed=Set.empty[String];var groups=Map.empty[String,PreparedOperatorGroup];var replacements=Map.empty[String,Task]
      val description=Json.obj();val units=description.putArray("execution_units")
      regions(plan,keys).foreach { ids=>
        val selected=ids.toSet;val members=plan.steps.filter(t=>selected(t.id))
        val external=members.flatMap(t=>t.inputs.toVector.sortBy(_._1).collect{case(p,b)if b.task.forall(id=> !selected(id))=>(t.id,p,b)})
        val ports=(plan.steps.filterNot(t=>selected(t.id)).flatMap(_.inputs.values.flatMap(b=>b.task.filter(selected).map(_->b.port)))++plan.outputs.values.filter(p=>selected(p._1))).distinct.sortBy(p=>p._1+":"+p._2)
        val sub=plan.copy(steps=members.map(t=>t.copy(inputs=t.inputs.map{case(p,b)=>p->(if(b.task.exists(id=> !selected(id)))InputBinding(uri=Some("unit:"+b.task.get+":"+b.port),view=b.view)else b)})),outputs=ports.zipWithIndex.map{case(p,i)=>s"export$i"->p}.toMap)
        if(ports.nonEmpty)OperatorGroups.prepareResolved(config,sub,operators.filter(p=>selected(p._1))) match {
          case Right(group)=>
            val id="unit-"+ids.head
            val bindings=external.zipWithIndex.map{case((_,_,b),i)=>s"i$i"->b}.toMap
            val t=group.task.copy(id=id,inputs=bindings)
            val g=group.copy(task=t,inputs=bindings)
            groups+=id->g;replacements+=ids.head->t;removed++=selected
            val labelToPort=group.outputs.map{case(port,(_,label))=>label->port}
            sub.outputs.foreach{case(label,source)=>routing+=source->(id,labelToPort(label))}
            val u=units.addObject().put("id",id).put("mode","shared_process").put("reason","same_locked_dependency_environment")
            OperatorLibrary.runtimeDescriptor(group.operator).fields.asScala.foreach(e=>u.set[JsonNode](e.getKey,e.getValue))
            val a=u.putArray("steps");ids.foreach(a.add)
          case Left(_)=>()
        }
      }
      if(groups.isEmpty)ExecutionUnits(plan,Map.empty,Json.obj())else {
        def bind(b:InputBinding):InputBinding=b.task.flatMap(t=>routing.get(t->b.port)).map{case(t,p)=>b.copy(task=Some(t),port=p)}.getOrElse(b)
        val steps=plan.steps.flatMap(t=>replacements.get(t.id).orElse(if(removed(t.id))None else Some(t))).map(t=>t.copy(inputs=t.inputs.view.mapValues(bind).toMap))
        plan.steps.filterNot(t=>removed(t.id)).foreach { t=>
          val u=units.addObject().put("id",t.id).put("mode",if(t.operator==Jobs.ReadItem)"host_stream_source"else "isolated_step").put("reason",if(t.operator==Jobs.ReadItem)"host_catalog_access"else "dependency_or_executor_boundary")
          u.putArray("steps").add(t.id);operators.get(t.id).foreach(op=>OperatorLibrary.runtimeDescriptor(op).fields.asScala.foreach(e=>u.set[JsonNode](e.getKey,e.getValue)))
        }
        ExecutionUnits(plan.copy(steps=steps,outputs=plan.outputs.view.mapValues(p=>routing.getOrElse(p,p)).toMap),groups,description)
      }
    }
  }
}
