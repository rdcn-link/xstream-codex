package xstream.engine


import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.{Json,YamlDocument}
import scala.jdk.CollectionConverters._
import _root_.xstream.execution._

final case class PushedRegion(id:String,node:String,plan:JobPlan,outputs:Map[(String,String),String])
final case class PushdownPlan(local:JobPlan,regions:Vector[PushedRegion],placements:Map[String,String],costs:JsonNode=Json.obj())
object PhysicalPlanner {
  /** Descriptions are checked before planning. Unreachable or unauthorized targets cannot receive work. */
  def select(logical:JobPlan,targets:Seq[ExecutionTarget],policy:ExecutionPolicy,cancel:StreamCancel):PushdownPlan={
    val placement=policy.effective.path("placement")
    def local=PushdownPlan(logical,Vector.empty,logical.steps.map(_.id->"local").toMap)
    if(placement.path("mode").asText("local")=="local" || policy.materialized.nonEmpty)return local
    val allowed=Option(placement.get("targets")).map(_.elements.asScala.map(_.asText).toSet)
    val descriptions=targets.filter(t=>allowed.forall(_(t.id))).flatMap { t=>
      cancel.active()
      try {val d=t.describe(cancel);if(d.canSubmit && d.available)Some(t->d)else None}
      catch{case scala.util.control.NonFatal(_)=>cancel.active();None}
    }
    val pushed=plan(logical,descriptions.map(_._1),policy)
    if(pushed.regions.isEmpty)return local
    val used=descriptions.filter(d=>pushed.regions.exists(_.node==d._1.id))
    // No invented selectivity: without estimates retain the known local execution plan.
    if(used.exists(d=>d._2.estimatedInputBytes.isEmpty || d._2.estimatedOutputBytes.isEmpty))
      return local.copy(costs=Json.obj().put("reason","missing_cost_estimates"))
    val inputBytes=used.map(d=>BigInt(d._2.estimatedInputBytes.get)).sum
    val outputBytes=used.map(d=>BigInt(d._2.estimatedOutputBytes.get)).sum
    val objective=placement.path("objective").asText("latency")
    val bandwidth=used.map(_._2.transferBytesPerSecond).min
    val baseline=PlacementCandidate("local",inputBytes,bandwidth=bandwidth)
    val remote=PlacementCandidate("source_regions",outputBytes,used.map(_._2.startupMillis).sum,bandwidth)
    val selected=PlacementOptimizer.choose(Seq(baseline,remote),objective)
    val costs=Json.obj().put("objective",objective).put("selected",selected.target).put("basis","host_estimates")
    val candidates=costs.putArray("candidates")
    Seq(baseline,remote).foreach(c=>candidates.addObject().put("plan",c.target).put("transfer_bytes",c.transferBytes.toString).put("latency_millis",c.latencyMillis.toString))
    (if(selected.target=="local")local else pushed).copy(costs=costs)

  }
  /** Only closed source-side regions move: remote execution never needs access to local context. */
  def plan(logical:JobPlan,targets:Seq[ExecutionTarget],policy:ExecutionPolicy):PushdownPlan={
    // Keep explicit persistence/replay ownership and its original task/port keys on this engine.
    if(policy.materialized.nonEmpty)return PushdownPlan(logical,Vector.empty,logical.steps.map(_.id->"local:materialization_policy").toMap)
    val locations=scala.collection.mutable.Map.empty[String,Option[String]]
    logical.steps.foreach { t=>
      val location=Jobs.readBinding(t) match {
        case Some(b)=>b.uri.flatMap(uri=>targets.find(_.localize(uri).nonEmpty).map(_.id))
        case None=>
          val inputs=t.inputs.values.toVector
          val upstream=inputs.flatMap(_.task).map(id=>locations(id))
          if(t.source.isEmpty && t.localOperator.isEmpty && inputs.nonEmpty && upstream.size==inputs.size && upstream.forall(_.nonEmpty) && upstream.distinct.size==1)upstream.head else None
      }
      locations(t.id)=location
    }
    val edges=ExecutionPolicy.connected(logical)
    val candidates=logical.steps.flatMap(t=>locations(t.id).map(_->t)).groupBy(_._1).toVector.sortBy(_._1)
    val regions=candidates.flatMap {case(node,entries)=>
      val steps=entries.map(_._2);val ids=steps.map(_.id).toSet
      if(!steps.exists(_.operator!=Jobs.ReadItem))None else {
        val external=edges.filter {case(id,port)=>ids(id) && (logical.outputs.values.toSet.contains(id->port) || logical.steps.exists(t=> !ids(t.id) && t.inputs.values.exists(b=>b.task.contains(id) && b.port==port)))}
        val outputs=external.toVector.sorted.zipWithIndex.map{case(pair,i)=>pair->s"p$i"}.toMap
        val remote=steps.map { t=>
          if(t.operator!=Jobs.ReadItem)t.copy(node=None) else {
            val params=new java.util.LinkedHashMap[String,Object](t.params)
            val uri=params.get("uri").toString
            params.put("uri",targets.find(_.id==node).get.localize(uri).get)
            t.copy(node=None,params=params)
          }
        }
        Some(PushedRegion(steps.last.id,node,JobPlan(remote,outputs.map{case(pair,label)=>label->pair},logical.name.take(247)+" / remote"),outputs))
      }
    }
    val member=regions.flatMap(r=>r.plan.steps.map(t=>t.id->r)).toMap
    def remap(task:String,port:String):(String,String)=member.get(task).map(r=>r.id->r.outputs(task->port)).getOrElse(task->port)
    val physical=logical.steps.flatMap { t=>member.get(t.id) match {
      case Some(region) if region.id!=t.id=>None
      case Some(region)=>Some(t.copy(inputs=Map.empty,outputPorts=region.outputs.values.toSeq.sorted))
      case None=>Some(t.copy(inputs=t.inputs.map {case(p,b)=>p->b.task.map { id=>val (task,port)=remap(id,b.port);b.copy(task=Some(task),port=port)}.getOrElse(b)}))
    }}
    val outputs=logical.outputs.map{case(label,(task,port))=>label->remap(task,port)}
    PushdownPlan(logical.copy(steps=physical,outputs=outputs),regions,logical.steps.map(t=>t.id->member.get(t.id).map(_.node).getOrElse("local")).toMap)
  }
  def yaml(plan:JobPlan):String={
    val n=Json.obj().put("name",plan.name);val steps=n.putArray("steps")
    plan.steps.foreach { t=>
      require(t.source.isEmpty && t.localOperator.isEmpty,"Remote execution requires repository operators")
      val task=steps.addObject().put("id",t.id).put("operator",t.operator);t.title.foreach(task.put("title",_));t.description.foreach(task.put("description",_))
      task.set[JsonNode]("params",Json.mapper.valueToTree(t.params));val inputs=task.putObject("inputs")
      t.inputs.toVector.sortBy(_._1).foreach{case(p,b)=>inputs.put(p,b.task.get+":"+b.port)}
    }
    val outputs=n.putObject("outputs");plan.outputs.toVector.sortBy(_._1).foreach{case(label,(task,port))=>outputs.put(label,task+":"+port)}
    YamlDocument.dump(n)
  }
}
