package xstream.galaxy

import java.io._
import java.nio.charset.{StandardCharsets,CodingErrorAction}
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._
import xstream.operator.{TransformOperator,ByteStreamOperator,OperatorContext}
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}

/** Streaming adaptations of Galaxy tabular-to-FASTA and SAM utilities. */
abstract class AlignmentOperator(val operation:String) extends TransformOperator {
 private def active():Unit=if(Thread.currentThread.isInterrupted)throw new CancellationException("operator cancelled")
 private def integer(p:JsonNode,key:String,default:Int,max:Int):Int={
  val n=p.get(key);require(n==null||n.isIntegralNumber&&n.canConvertToInt,key+" must be an integer")
  val value=if(n==null)default else n.asInt;require(value>=0&&value<=max,key+" outside allowed range");value
 }
 private def titleColumns(p:JsonNode):Vector[Int]={
  require(!p.has("title_columns")||p.path("title_columns").isTextual,"title_columns must be text")
  val text=p.path("title_columns").asText("1");require(text.length<=4096,"title_columns too long")
  val cols=text.split(",",-1).toVector
  require(cols.nonEmpty&&cols.size<=128&&cols.forall(_.matches("[1-9][0-9]{0,4}")),"use comma-separated one-based title columns")
  cols.map{s=>val n=s.toInt;require(n<=65536,"title column exceeds 65536");n-1}
 }
 private def validate(p:JsonNode):Unit={
  require(p!=null&&p.isObject,"parameters must be an object")
  val keys=operation match{case "tabular_to_fasta"=>Set("title_columns","sequence_column");case "sam_filter_flags"=>Set("require_flags","exclude_flags");case _=>Set("print_all")}
  require(p.fieldNames.asScala.forall(keys),"unknown parameter")
  operation match{
   case "tabular_to_fasta"=>titleColumns(p);require(integer(p,"sequence_column",2,65536)>0,"sequence_column must be positive")
   case "sam_filter_flags"=>val yes=integer(p,"require_flags",0,65535);val no=integer(p,"exclude_flags",0,65535);require((yes & no)==0,"required and excluded flags overlap")
   case _=>require(!p.has("print_all")||p.path("print_all").isBoolean,"print_all must be boolean")
  }
 }
 private val delegate=new ByteStreamOperator {
  override def outputFormat:String=operation match{case "tabular_to_fasta"=>"fasta";case "sam_filter_flags"=>"sam";case _=>"tsv"}
  def transform(input:InputStream,output:OutputStream,p:JsonNode):Unit={
   validate(p);active()
   val reader=new PushbackReader(new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder.onMalformedInput(CodingErrorAction.REPORT)),8192),1)
   val out=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),8192)
   val titles=if(operation=="tabular_to_fasta")titleColumns(p)else Vector.empty
   val sequence=integer(p,"sequence_column",2,65536)-1
   val yes=integer(p,"require_flags",0,65535);val no=integer(p,"exclude_flags",0,65535)
   def emit(text:String):Unit={active();require(text.length<=2097152,"output row exceeds 2 MiB characters");out.write(text);out.write("\n")}
   if(operation=="sam_to_interval")emit("#chrom\tstart\tend\tstrand\tread_name")
   def row(line:String):Unit={
    active()
    if(line.nonEmpty && !line.startsWith("#") && (operation=="tabular_to_fasta" || !line.startsWith("@"))){
     val fields=line.split("\t",-1)
     if(operation=="tabular_to_fasta"){
      // Galaxy skips rows missing any selected column.
      if(sequence<fields.length&&titles.forall(_<fields.length)){
       val outputSize=titles.foldLeft(0L)((sum,i)=>sum+fields(i).length)+titles.size+fields(sequence).length+3
       require(outputSize<=2097152,"output row exceeds 2 MiB characters")
       val header=titles.map(fields(_)).updated(0,fields(titles.head).stripPrefix(">")).mkString("_")
       emit(">"+header+"\n"+fields(sequence))
      }
     }else {
      require(fields.length>=11,"SAM alignment requires at least 11 fields")
      val flags=fields(1).toInt;require(flags>=0&&flags<=65535,"invalid SAM flag")
      if(operation=="sam_filter_flags"){
       if((flags & yes)==yes && (flags & no)==0)emit(line)
      }else if(fields(2)!="*" && (flags & 4)==0){
       val position=fields(3).toLong;require(position>0,"mapped SAM position must be positive")
       val cigar=fields(5);val pattern="([0-9]+)([MIDNSHP=X])".r
       val matches=pattern.findAllMatchIn(cigar).toVector
       require(matches.nonEmpty&&matches.map(_.matched).mkString==cigar,"invalid mapped SAM CIGAR")
       var span=0L
       matches.foreach{m=>val length=m.group(1).toLong;require(length>0,"CIGAR lengths must be positive");if(Set("M","D","N","=","X")(m.group(2)))span=Math.addExact(span,length)}
       require(span>0,"mapped CIGAR must consume reference bases")
       val start=position-1;val end=Math.addExact(start,span);val strand=if((flags & 16)==0)"+"else"-"
       emit(Vector(fields(2),start.toString,end.toString,strand,if(p.path("print_all").asBoolean(false))line else fields(0)).mkString("\t"))
      }
     }
    }
   }
   val line=new java.lang.StringBuilder();var ch=reader.read();var count=0
   while(ch != -1){
    if((count & 4095)==0)active();count+=1
    if(ch=='\r'||ch=='\n'){
     if(ch=='\r'){val next=reader.read();if(next != -1&&next!='\n')reader.unread(next)}
     row(line.toString);line.setLength(0)
    }else{require(line.length<1048576,"line exceeds 1 MiB characters");line.append(ch.toChar)}
    ch=reader.read()
   }
   if(line.length>0)row(line.toString);active();out.flush()
  }
 }
 def transform(in:InputStream,out:OutputStream,p:JsonNode):Unit=delegate.transform(in,out,p)
 def inferOutputs(in:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={validate(p);delegate.inferOutputs(in,p)}
 def previewOutputs(in:Map[String,StreamDescriptor],p:JsonNode,l:PreviewLimits):Map[String,OutputSample]={validate(p);delegate.previewOutputs(in,p,l)}
 def process(ctx:OperatorContext):Unit={validate(ctx.params);delegate.process(ctx)}
}
object TabularToFasta extends AlignmentOperator("tabular_to_fasta")
object SamFilterFlags extends AlignmentOperator("sam_filter_flags")
object SamToInterval extends AlignmentOperator("sam_to_interval")
