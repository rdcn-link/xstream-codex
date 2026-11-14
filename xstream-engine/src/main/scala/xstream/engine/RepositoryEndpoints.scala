package xstream.engine

import xstream.operator.StreamEndpoint
import xstream.operator.inference.{OperatorEnvironment,OperatorLibrary}

/** Transport lowering is allowed only after the repository package has been resolved and verified. */
object RepositoryEndpoints {
  def source(config:OperatorEnvironment,reference:String=Jobs.ItemSource):Unit={
    OperatorLibrary.requireStreamEndpoint(config,reference,StreamEndpoint.CatalogRead);()
  }
  def validate(config:OperatorEnvironment,plan:JobPlan,check:()=>Unit=()=>()):Unit={
    if(plan.steps.exists(_.operator==Jobs.ItemSource)){check();source(config)}
    if(plan.requiredOperators(Jobs.ClientSink) || plan.outputs.nonEmpty){
      check();OperatorLibrary.requireStreamEndpoint(config,Jobs.ClientSink,StreamEndpoint.ClientReceive)
    }
  }
}
