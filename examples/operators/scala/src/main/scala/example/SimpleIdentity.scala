package example


import com.fasterxml.jackson.databind.JsonNode
import xstream.operator.BytesOperator
import _root_.xstream.execution.{StreamDescriptor,PreviewLimits,OutputSample}


/** The SDK owns protocol, input discovery and stream cleanup. */
object SimpleIdentity extends BytesOperator {
  override def previewOutputs(inputs:Map[String,StreamDescriptor],params:JsonNode,limits:PreviewLimits):Map[String,OutputSample]=
    inputs.get("source").flatMap(_.sample).map(s=>Map("result"->s.bounded(limits))).getOrElse(Map.empty)
  override def transform(chunk:Array[Byte],params:JsonNode):Array[Byte]=chunk
}
