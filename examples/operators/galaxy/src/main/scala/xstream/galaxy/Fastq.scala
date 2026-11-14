package xstream.galaxy

import java.io._
import java.nio.charset.{StandardCharsets,CodingErrorAction}
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._
import xstream.operator.{TransformOperator,ByteStreamOperator,OperatorContext}
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}

/** Four-line FASTQ conversion with explicit quality encoding and bounded records. */
abstract class FastqOperator(qualityOutput:Boolean) extends TransformOperator {
 private def active():Unit=if(Thread.currentThread.isInterrupted)throw new CancellationException("operator cancelled")
 private def encoding(p:JsonNode):String={
  require(p!=null&&p.isObject&&p.fieldNames.asScala.forall(_=="encoding"),"only encoding is supported")
  require(!p.has("encoding")||p.path("encoding").isTextual,"encoding must be text")
  val e=p.path("encoding").asText("solexa64")
  require(Set("solexa64","sanger33","integers")(e),"unsupported quality encoding");e
 }
 private val delegate=new ByteStreamOperator {
  override def outputFormat:String=if(qualityOutput)"qual"else"fasta"
  def transform(input:InputStream,output:OutputStream,p:JsonNode):Unit={
   val e=encoding(p);active()
   val in=new PushbackReader(new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder.onMalformedInput(CodingErrorAction.REPORT))),1)
   val out=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8))
   def line():Option[String]={
    val b=new java.lang.StringBuilder;var c=in.read();if(c == -1)return None
    while(c != -1&&c!='\n'&&c!='\r'){if((b.length&4095)==0)active();require(b.length<1048576,"FASTQ line exceeds 1 MiB characters");b.append(c.toChar);c=in.read()}
    if(c=='\r'){val n=in.read();if(n != -1&&n!='\n')in.unread(n)};Some(b.toString)
   }
   def required():String=line().getOrElse(throw new IllegalArgumentException("truncated four-line FASTQ record"))
   var header=line()
   while(header.nonEmpty){
    active();val h=header.get;require(h.startsWith("@")&&h.length>1,"FASTQ header must start with @ and have a title")
    val seq=required();val plus=required();val q=required()
    require(seq.forall(c=>c>32&&c<127),"sequence must contain printable non-whitespace ASCII")
    require(plus.startsWith("+")&&(plus.length==1||plus.substring(1)==h.substring(1)),"FASTQ + title must match read title")
    val scores=if(e=="integers"){
     val parts=if(q.trim.isEmpty)Array.empty[String]else q.trim.split("\\s+")
     require(parts.length==seq.length,"quality count must equal sequence length")
     parts.map{v=>require(v.matches("-?[0-9]{1,3}"),"invalid integer quality");val n=v.toInt;require(n>= -5&&n<=93,"integer quality outside -5..93");n}
    }else{
     require(q.length==seq.length,"quality count must equal sequence length")
     val base=if(e=="sanger33")33 else 64;val low=if(e=="sanger33")33 else 59
     q.map{c=>require(c>=low&&c<=126,"quality character outside encoding range");c.toInt-base}.toArray
    }
    out.write(">");out.write(h.substring(1));out.write("\n")
    out.write(if(qualityOutput)scores.mkString(" ")else seq);out.write("\n");header=line()
   };active();out.flush()
  }
 }
 def transform(i:InputStream,o:OutputStream,p:JsonNode):Unit=delegate.transform(i,o,p)
 def inferOutputs(i:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={encoding(p);delegate.inferOutputs(i,p)}
 def previewOutputs(i:Map[String,StreamDescriptor],p:JsonNode,l:PreviewLimits):Map[String,OutputSample]={encoding(p);delegate.previewOutputs(i,p,l)}
 def process(c:OperatorContext):Unit={encoding(c.params);delegate.process(c)}
}
object FastqToFasta extends FastqOperator(false)
object FastqToQual extends FastqOperator(true)
