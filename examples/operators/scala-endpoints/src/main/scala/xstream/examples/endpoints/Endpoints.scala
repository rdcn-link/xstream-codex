package xstream.examples.endpoints

import com.fasterxml.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets.UTF_8
import xstream.execution.{InferenceResult,OutputSample,PreviewLimits,StreamDescriptor}
import xstream.operator.{Operator,OperatorContext}

private[endpoints] object Parameters {
  def fields(p:JsonNode,required:Set[String],optional:Set[String]=Set.empty):Unit={
    import scala.jdk.CollectionConverters._
    require(p!=null && p.isObject && required.subsetOf(p.fieldNames().asScala.toSet) && p.fieldNames().asScala.toSet.subsetOf(required++optional),"invalid parameters")
  }
  def text(p:JsonNode,key:String,max:Int):String={
    val n=p.path(key);require(n.isTextual && n.asText.length<=max,"invalid "+key);n.asText
  }
  def number(p:JsonNode,key:String,min:Long,max:Long,default:Long):Long={
    val n=p.path(key);if(n.isMissingNode)default else {require(n.isIntegralNumber && n.canConvertToLong && n.asLong>=min && n.asLong<=max,"invalid "+key);n.asLong}
  }
  def bytes(format:String)=InferenceResult(Map("result"->StreamDescriptor(Set("bytes"),Set(format),certainty="known")))
  def sample(format:String,raw:Array[Byte],limits:PreviewLimits,truncated:Boolean=false):Map[String,OutputSample]={
    val take=raw.take(math.min(raw.length,limits.maxBytes/2))
    Map("result"->OutputSample("bytes",format,data=take.toVector,truncated=truncated||take.length<raw.length).bounded(limits))
  }
}

/** Emits bounded UTF-8 text without an input port. */
object TextSource extends Operator {
  import Parameters._
  private def values(p:JsonNode):(Array[Byte],Int)={
    fields(p,Set("text"),Set("repeat"));val bytes=text(p,"text",4096).getBytes(UTF_8)
    val repeat=number(p,"repeat",0,10000,1).toInt
    require(bytes.length.toLong*repeat<=8L*1024*1024,"generated text exceeds 8 MiB")
    (bytes,repeat)
  }
  def inferOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={require(inputs.isEmpty,"Source has no input ports");values(p);bytes("text")}
  def previewOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode,limits:PreviewLimits):Map[String,OutputSample]={
    val (value,repeat)=values(p);val out=new java.io.ByteArrayOutputStream()
    var n=0;while(n<repeat && out.size()<limits.maxBytes/2){out.write(value);n+=1}
    sample("text",out.toByteArray,limits,n<repeat)
  }
  def process(ctx:OperatorContext):Unit={
    require(ctx.inputNames.isEmpty,"Source has no input ports")
    val (value,repeat)=values(ctx.params);val out=ctx.bytesOutput("result","text")
    var n=0;while(n<repeat){out.write(value);n+=1}
  }
}

/** Generates a newline-delimited integer stream, without staging a file. */
object RangeSource extends Operator {
  import Parameters._
  private def values(p:JsonNode):(Long,Int)={
    fields(p,Set("start","count"))
    val start=number(p,"start",Long.MinValue,Long.MaxValue,0)
    val count=number(p,"count",0,100000,0).toInt
    if(count>0)Math.addExact(start,count.toLong-1)
    (start,count)
  }
  def inferOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={require(inputs.isEmpty,"Source has no input ports");values(p);bytes("text")}
  def previewOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode,limits:PreviewLimits):Map[String,OutputSample]={
    val (start,count)=values(p);val out=new java.io.ByteArrayOutputStream();var n=0
    while(n<count && n<100 && out.size()<limits.maxBytes/2){out.write((start+n).toString.getBytes(UTF_8));out.write('\n');n+=1}
    sample("text",out.toByteArray,limits,n<count)
  }
  def process(ctx:OperatorContext):Unit={
    require(ctx.inputNames.isEmpty,"Source has no input ports")
    val (start,count)=values(ctx.params);val out=ctx.bytesOutput("result","text")
    val chunk=new StringBuilder;var n=0
    while(n<count){chunk.append(start+n).append('\n');if(chunk.length>=32768){out.write(chunk.toString.getBytes(UTF_8));chunk.clear()};n+=1}
    if(chunk.nonEmpty)out.write(chunk.toString.getBytes(UTF_8))
  }
}

/** Consumes all bytes and fails the Job unless the requested UTF-8 text occurs. */
object ContainsTextSink extends Operator {
  import Parameters._
  private def needle(p:JsonNode):Array[Byte]={fields(p,Set("text"));val value=text(p,"text",256).getBytes(UTF_8);require(value.nonEmpty,"text must not be empty");value}
  def inferOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={require(inputs.keySet==Set("source") && inputs("source").representations("bytes"),"bytes source required");needle(p);InferenceResult(Map.empty)}
  def previewOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode,limits:PreviewLimits):Map[String,OutputSample]=Map.empty
  def process(ctx:OperatorContext):Unit={
    val pattern=needle(ctx.params);val prefix=new Array[Int](pattern.length)
    var i=1;var j=0;while(i<pattern.length){while(j>0 && pattern(i)!=pattern(j))j=prefix(j-1);if(pattern(i)==pattern(j))j+=1;prefix(i)=j;i+=1}
    val in=ctx.input("source").byteStream();var matched=false;j=0
    try {var value=in.read();while(value>=0){
      val b=value.toByte;while(j>0 && b!=pattern(j))j=prefix(j-1)
      if(b==pattern(j))j+=1
      if(j==pattern.length){matched=true;j=prefix(j-1)}
      value=in.read()
    }}finally in.close()
    require(matched,"required text was not found")
  }
}

/** Consumes all bytes and checks the minimum number of newline-delimited lines. */
object MinLinesSink extends Operator {
  import Parameters._
  private def minimum(p:JsonNode):Long={fields(p,Set("minimum"));number(p,"minimum",0,100000000,0)}
  def inferOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={require(inputs.keySet==Set("source") && inputs("source").representations("bytes"),"bytes source required");minimum(p);InferenceResult(Map.empty)}
  def previewOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode,limits:PreviewLimits):Map[String,OutputSample]=Map.empty
  def process(ctx:OperatorContext):Unit={
    val min=minimum(ctx.params);val in=ctx.input("source").byteStream()
    var lines=0L;var seen=false;var last=0
    try {var value=in.read();while(value>=0){seen=true;if(value=='\n')lines+=1;last=value;value=in.read()}}finally in.close()
    if(seen && last!='\n')lines+=1
    require(lines>=min,s"expected at least $min lines; found $lines")
  }
}
