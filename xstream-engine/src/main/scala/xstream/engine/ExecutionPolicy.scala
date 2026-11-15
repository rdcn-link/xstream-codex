package xstream.engine

import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.Json

/** Failed attempts are discarded; retries may reuse explicitly selected complete outputs. */
final case class ExecutionPolicy(submitted:JsonNode,effective:JsonNode,maxRetries:Int,timeoutSeconds:Option[Long],resources:ExecutionResources,checkpoints:Set[(String,String)]=Set.empty)
object ExecutionPolicy {
  def connected(plan:JobPlan):Set[(String,String)]=plan.outputs.values.toSet++plan.steps.flatMap(_.inputs.values.flatMap(b=>b.task.map(_->b.port)))
  def parse(raw:JsonNode,plan:JobPlan):ExecutionPolicy={
    Json.fields(raw,Set.empty,Set("max_retries","timeout_seconds","resources","checkpoints"))
    val resources=ExecutionResources.parse(Option(raw.get("resources")).getOrElse(Json.obj()))
    val maxRetries=if(raw.has("max_retries"))Json.number(raw,"max_retries",0,10).toInt else 0
    val timeout=if(raw.has("timeout_seconds"))Some(Json.number(raw,"timeout_seconds",1,86400))else None
    val cp=Option(raw.get("checkpoints")).getOrElse(Json.obj())
    require(cp.isObject,"checkpoints must map step IDs to output-port lists")
    val connectedPorts=connected(plan);val ids=plan.steps.map(_.id).toSet
    import scala.jdk.CollectionConverters._
    val selected=cp.fields.asScala.flatMap { e=>
      require(ids(e.getKey),"unknown checkpoint step: "+e.getKey)
      require(e.getValue.isArray && e.getValue.size()>0,"checkpoint outputs must be a nonempty list")
      val ports=e.getValue.elements.asScala.toVector
      require(ports.forall(p=>p.isTextual && connectedPorts(e.getKey->p.asText)),"unknown or unconnected checkpoint output: "+e.getKey)
      require(ports.map(_.asText).distinct.size==ports.size,"duplicate checkpoint output")
      ports.map(p=>e.getKey->p.asText)
    }.toSet
    // A streaming sibling must not block a producer whose checkpoint gates that
    // sibling's eventual consumer. Such a plan cannot complete with bounded queues.
    def descendants(id:String):Set[String]={
      val found=scala.collection.mutable.Set.empty[String]
      def visit(parent:String):Unit=if(found.add(parent))plan.steps.filter(t=>Jobs.dependencies(t).contains(parent)).foreach(t=>visit(t.id))
      visit(id);found.toSet
    }
    selected.groupBy(_._1).foreach { case(producer,_)=>
      val checkpointed=plan.steps.filter(_.inputs.values.exists(b=>b.task.contains(producer) && selected(producer->b.port))).flatMap(t=>descendants(t.id)).toSet
      val streamed=plan.steps.filter(_.inputs.values.exists(b=>b.task.contains(producer) && !selected(producer->b.port))).flatMap(t=>descendants(t.id)).toSet
      require(checkpointed.intersect(streamed).isEmpty,"streaming and checkpointed outputs rejoin; checkpoint the other connected outputs of "+producer+" as well")
    }
    val effective=Json.obj().put("max_retries",maxRetries).put("retry_scope",if(selected.isEmpty)"job"else"checkpoints")
    effective.set[JsonNode]("checkpoints",cp.deepCopy[JsonNode]())
    effective.set[JsonNode]("resources",resources.json);timeout.foreach(effective.put("timeout_seconds",_))
    ExecutionPolicy(raw.deepCopy[JsonNode](),effective,maxRetries,timeout,resources,selected)
  }
}
