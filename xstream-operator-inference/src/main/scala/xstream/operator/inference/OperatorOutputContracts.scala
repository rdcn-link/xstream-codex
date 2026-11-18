package xstream.operator.inference

import com.fasterxml.jackson.databind.JsonNode
import xstream.execution.{PortContract,StreamDescriptor,InferenceResult}
import xstream.operator.OutputViews
import org.apache.arrow.vector.types.pojo.Schema
import scala.jdk.CollectionConverters._

object OperatorOutputContracts {
  def ports(declaration:JsonNode,params:JsonNode,inputs:Map[String,Set[String]],allowPending:Boolean=false,availableViews:Set[String]=Set.empty):Map[String,PortContract]={
    OutputViews.resolve(declaration,params,inputs).map {case(port,view)=>
      val d=declaration.path("outputs").path(port)
      require(allowPending || view.nonEmpty || d.path("view").asText=="variant" && !d.has("view_from") && availableViews.nonEmpty,"output View prerequisites unavailable for port "+port)
      val views=if(!allowPending && view.isEmpty)availableViews else view.toSet
      port->PortContract(views,d.path("formats").elements.asScala.map(_.asText).toSet,Option(d.get("schema")).map(s=>Schema.fromJSON(s.toString)),Option(d.get("cardinality")).map(_.asText))
    }
  }
  def bind(op:ResolvedOperator,params:JsonNode,inputs:Map[String,Set[String]],allowPending:Boolean=false,availableViews:Set[String]=Set.empty):ResolvedOperator=op.declaration.map {d=>
    val outputs=ports(d,params,inputs,allowPending,availableViews)
    op.copy(outputs=outputs,program=op.program.map(_.copy(outputs=outputs.view.mapValues(_.representations).toMap)))
  }.getOrElse(op)
  def validate(op:ResolvedOperator,params:JsonNode,inputs:Map[String,StreamDescriptor],result:InferenceResult):InferenceResult={
    val bound=bind(op,params,inputs.view.mapValues(_.representations).toMap,allowPending=true)
    require(result.outputs.keySet==bound.outputs.keySet,"inferred output ports do not match operator declaration")
    result.copy(outputs=result.outputs.map {case(port,d)=>
      val expected=bound.outputs(port)
      require(expected.representations.size<=1,"output View must resolve to one type")
      require(d.representations.size<=1 && d.representations.forall(xstream.operator.OutputViews.isConcrete),"inference must return a concrete View or leave a variant View unresolved")
      require(expected.representations.isEmpty || d.representations.isEmpty || d.representations==expected.representations,"inferred output View does not match declaration on "+port)
      require(d.formats.isEmpty || expected.formats.isEmpty || d.formats.subsetOf(expected.formats),"inferred output format does not match declaration on "+port)
      for(a<-expected.schema;b<-d.schema)require(a==b,"inferred output schema does not match declaration on "+port)
      d.structure.foreach(s=>require(s.path("view").asText==(if(expected.representations.nonEmpty)expected.representations.head else d.representations.headOption.getOrElse("")),"inferred structure View does not match declaration on "+port))
      expected.cardinality.foreach(c=>require(d.structure.exists(_.path("cardinality").asText==c),"inferred output cardinality does not match declaration on "+port))
      port->d.copy(representations=if(expected.representations.nonEmpty)expected.representations else d.representations,schema=d.schema.orElse(expected.schema))
    })
  }
}
