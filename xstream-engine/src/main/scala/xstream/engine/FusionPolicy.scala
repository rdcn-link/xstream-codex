package xstream.engine

import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.Json

/** A conservative structural cost estimate, not a measured latency prediction. */
object FusionPolicy {
  val modes=Set("isolated","fused","auto")
  def parse(raw:Option[JsonNode]):String={
    require(raw.forall(n=>n.isTextual && modes(n.asText)),"operator_fusion must be isolated, fused or auto")
    raw.map(_.asText).getOrElse("auto")
  }
  final case class Decision(allowed:Boolean,reason:String,savedCost:Long,parallelismCost:Long) {
    def json:JsonNode=Json.obj().put("allowed",allowed).put("reason",reason)
      .put("saved_cost_units",savedCost).put("parallelism_cost_units",parallelismCost)
      .put("model","structural_v1").put("confidence","low")
  }
  def decide(mode:String,plan:JobPlan):Decision={
    require(modes(mode),"operator_fusion must be isolated, fused or auto")
    val steps=plan.steps.filterNot(_.operator==Jobs.ItemSource)
    val ids=steps.map(_.id).toSet
    val edges=steps.flatMap(t=>t.inputs.values.flatMap(_.task).filter(ids).map(_->t.id))
    // Startup dominates the proxy; branching carries a conservative parallelism penalty.
    // Unknown input sizes and operator working sets are deliberately not invented.
    val saved=math.max(0,steps.size-1).toLong*100+edges.size
    val branches=edges.groupBy(_._1).values.map(e=>math.max(0,e.map(_._2).distinct.size-1)).sum+
      steps.map(t=>math.max(0,t.inputs.size-1)).sum
    val penalty=branches.toLong*(steps.size.toLong*100+edges.size)
    val connected=steps.size>1 && edges.distinct.size>=steps.size-1 &&
      steps.count(t=> !edges.exists(_._2==t.id))==1
    mode match {
      case "isolated"=>Decision(false,"isolated_by_policy",saved,penalty)
      case "fused"=>Decision(true,"fused_by_policy",saved,penalty)
      case _=>
        val allow=connected && branches==0 && saved>penalty
        Decision(allow,if(allow)"auto_lower_estimated_cost"else"auto_preserve_parallelism",saved,penalty)
    }
  }
}
