package xstream.examples.items

import xstream.operator.{SourceOperator,TransformOperator,OperatorContext}
import xstream.data._
import xstream.execution.{InferenceResult,OutputSample,PreviewLimits,StreamDescriptor}
import com.fasterxml.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets.UTF_8

abstract class ItemCollectionSource extends SourceOperator {
  protected def mode:String
  protected def selection(params:JsonNode):JsonNode
  final def inferOutputs(inputs:Map[String,StreamDescriptor],params:JsonNode):InferenceResult={
    require(inputs.isEmpty,"Source has no inputs")
    require(params.path("uri").isTextual,"uri is required");ItemReferences.validate(params.path("uri").asText)
    val s=selection(params);new ItemsViewSupport().selection(s)
    InferenceResult(Map("result"->StreamDescriptor(Set("items"),Set("items"),certainty="known",structure=Some(new ItemsViewSupport().descriptor(ItemReferences.schema)))))
  }
  final def previewOutputs(inputs:Map[String,StreamDescriptor],params:JsonNode,limits:PreviewLimits):Map[String,OutputSample]=Map.empty
  final def process(ctx:OperatorContext):Unit={
    inferOutputs(Map.empty,ctx.params)
    val output=ctx.itemsOutput("result")
    ctx.openItem(ctx.params.path("uri").asText,"items",selection(ctx.params)){stream=>
      ItemReferences.foreach(stream)(output.write)
    }
  }
}
object FolderItemsSource extends ItemCollectionSource {
  protected val mode="folder"
  protected def selection(params:JsonNode):JsonNode={
    require(!params.has("recursive") || params.path("recursive").isBoolean,"recursive must be boolean")
    Data.mapper.createObjectNode().put("mode",mode).put("recursive",params.path("recursive").asBoolean(false))
  }
}
object CsvItemsSource extends ItemCollectionSource {
  protected val mode="csv"
  protected def selection(params:JsonNode):JsonNode={
    require(!params.has("uri_column") || params.path("uri_column").isTextual && params.path("uri_column").asText.nonEmpty,"uri_column must be a nonempty string")
    Data.mapper.createObjectNode().put("mode",mode).put("uri_column",params.path("uri_column").asText("uri"))
  }
}
/** One invocation handles the whole collection; it chooses whether to open any member. */
object ItemInventory extends TransformOperator {
  def inferOutputs(inputs:Map[String,StreamDescriptor],params:JsonNode):InferenceResult={
    require(inputs.keySet==Set("source") && inputs("source").representations==Set("items"),"expected source items input")
    InferenceResult(Map("result"->StreamDescriptor(Set("bytes"),Set("text"),certainty="known")))
  }
  def previewOutputs(inputs:Map[String,StreamDescriptor],params:JsonNode,limits:PreviewLimits):Map[String,OutputSample]=Map.empty
  def process(ctx:OperatorContext):Unit={
    val output=ctx.bytesOutput("result","text")
    ctx.input("source").foreachItem { item=>output.write(s"${item.uri}\t${item.kind}\t${item.format}\n".getBytes(UTF_8)) }
  }
}

/** Demonstrates bounded lazy reads; directory-only references have no byte content. */
object ItemByteCount extends TransformOperator {
  def inferOutputs(inputs:Map[String,StreamDescriptor],params:JsonNode):InferenceResult=
    ItemInventory.inferOutputs(inputs,params)
  def previewOutputs(inputs:Map[String,StreamDescriptor],params:JsonNode,limits:PreviewLimits):Map[String,OutputSample]=Map.empty
  def process(ctx:OperatorContext):Unit={
    val output=ctx.bytesOutput("result","text")
    ctx.input("source").foreachItem { item=>
      if(item.kind!="folder" || item.views.exists(_.contains("bytes"))) {
        var count=0L
        ctx.openItem(item,"bytes") { stream=>
          ArrowBlocks.consume(stream,new DataConsumer[ByteChunk] {
            def start(d:DataDescriptor[ByteChunk]):Unit=()
            def accept(chunk:ByteChunk):Unit={count=Math.addExact(count,chunk.length.toLong)}
          })
        }
        output.write(s"${item.uri}\t$count\n".getBytes(UTF_8))
      }
    }
  }
}
