package xstream.galaxy

import java.io._
import java.nio.charset.{StandardCharsets,CodingErrorAction}
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._
import xstream.operator.{TransformOperator,ByteStreamOperator,OperatorContext}
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}

/** Bounded FASTA and line selection tools, adapted from the pinned Galaxy scripts. */
abstract class SequenceOperator(val operation:String) extends TransformOperator {
  private def number(p:JsonNode,k:String,d:Int,max:Int):Int={
    val n=p.get(k);require(n==null || n.isIntegralNumber && n.canConvertToInt,k+" must be an integer")
    val v=if(n==null)d else n.asInt;require(v>=0 && v<=max,k+" is outside the allowed range");v
  }
  private def validate(p:JsonNode):Unit={
    require(p!=null && p.isObject,"parameters must be an object")
    val keys=operation match {
      case "fasta_length"=>Set("keep_first","id_only")
      case "fasta_filter_length"=>Set("min_length","max_length")
      case "fasta_to_tabular"=>Set("keep_first","description_columns")
      case _=>Set("lines")
    }
    require(p.fieldNames.asScala.forall(keys),"unknown parameter")
    if(operation=="fasta_length"||operation=="fasta_to_tabular")number(p,"keep_first",0,4096)
    if(operation=="fasta_length")require(!p.has("id_only")||p.path("id_only").isBoolean,"id_only must be boolean")
    if(operation=="fasta_filter_length"){
      val min=number(p,"min_length",0,1000000000);val max=number(p,"max_length",0,1000000000)
      require(max==0 || max>=min,"max_length must be zero or at least min_length")
    }
    if(operation=="fasta_to_tabular")require(number(p,"description_columns",1,32)>=1,"description_columns must be at least one")
    if(!operation.startsWith("fasta_"))number(p,"lines",10,if(operation=="tail")10000 else 1000000)
  }
  private def active():Unit=if(Thread.currentThread.isInterrupted)throw new CancellationException("operator cancelled")
  private def lines(input:InputStream)(f:(String,String)=>Unit):Unit={
    val r=new PushbackReader(new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)),8192),1)
    val b=new java.lang.StringBuilder();var ch=r.read();var count=0
    while(ch != -1){
      if((count & 4095)==0)active();count+=1
      if(ch=='\r'||ch=='\n'){
        var ending=ch.toChar.toString
        if(ch=='\r'){val next=r.read();if(next=='\n')ending="\r\n"else if(next != -1)r.unread(next)}
        f(b.toString,ending);b.setLength(0)
      }else {require(b.length<1048576,"line exceeds 1 MiB characters");b.append(ch.toChar)}
      ch=r.read()
    }
    if(b.length>0)f(b.toString,"")
    active()
  }
  private def truncate(s:String,n:Int):String=if(n==0 || s.codePointCount(0,s.length)<=n)s else s.substring(0,s.offsetByCodePoints(0,n))
  private val delegate=new ByteStreamOperator {
    override def outputFormat:String=operation match {case "fasta_filter_length"=>"fasta";case "fasta_length"|"fasta_to_tabular"=>"tsv";case _=>"text"}
    def transform(input:InputStream,output:OutputStream,p:JsonNode):Unit={
      validate(p);active();val out=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),8192)
      def write(s:String):Unit={active();out.write(s)}
      if(!operation.startsWith("fasta_")){
        val limit=number(p,"lines",10,if(operation=="tail")10000 else 1000000);var index=0L
        val tail=new java.util.ArrayDeque[String]();var chars=0L
        lines(input){(line,ending)=>
          index=Math.addExact(index,1L)
          operation match {
            case "remove_beginning"=>if(index>limit)write(line+ending)
            case "head"=>if(index<=limit)write(line+ending)
            case "tail"=>if(limit>0){val s=line+ending;tail.addLast(s);chars+=s.length;if(tail.size>limit)chars-=tail.removeFirst().length;require(chars<=8388608,"tail buffer exceeds 8 MiB characters")}
          }
        }
        if(operation=="tail")tail.iterator.asScala.foreach(write)
      }else {
        var title:Option[String]=None;var length=0L;var haveOutput=false
        val record=new java.lang.StringBuilder();var discarded=false
        val min=number(p,"min_length",0,1000000000);val max=number(p,"max_length",0,1000000000)
        val keep=number(p,"keep_first",0,4096)
        def append(s:String):Unit={require(record.length.toLong+s.length<=8388608,"FASTA record exceeds 8 MiB characters");record.append(s)}
        def flush(last:Boolean):Unit=title.foreach { header=>
          operation match {
            case "fasta_length"=>val id=if(p.path("id_only").asBoolean(false))header.split("(?U)\\s+",2).head else header;write(truncate(id,keep)+"\t"+length+"\n")
            case "fasta_filter_length"=>if(!discarded && length>=min && (max==0 || length<=max)){
              val value=record.toString;write(if(last)value.replaceAll("[\\r\\n]+$","")else value)
            }
            case "fasta_to_tabular"=>()
          }
        }
        lines(input){(raw,_)=>
          val line=if(operation=="fasta_length")raw.strip()else raw
          if(line.nonEmpty && !line.startsWith("#")){
            if(line.startsWith(">")){
              flush(false);title=Some(line.drop(1));length=0;record.setLength(0);discarded=false
              if(operation=="fasta_filter_length")append(line+"\n")
              if(operation=="fasta_to_tabular"){
                if(haveOutput)write("\n");haveOutput=true
                val header=line.drop(1).replace('\t',' ');val columns=number(p,"description_columns",1,32)
                val fields=if(columns==1)Vector(truncate(header,keep))else {
                  val words=header.strip().split("(?U)\\s+",columns).toVector
                  words.updated(0,truncate(words.head,keep)).padTo(columns,"")
                }
                write(fields.mkString("\t")+"\t")
              }
            }else {
              require(title.nonEmpty,"FASTA sequence must follow a > header")
              length=Math.addExact(length,line.codePointCount(0,line.length).toLong)
              if(operation=="fasta_filter_length"){
                if(max>0 && length>max){discarded=true;record.setLength(0)}
                else if(!discarded)append(line+"\n")
              }
              if(operation=="fasta_to_tabular")write(line)
            }
          }
        }
        flush(true);if(operation=="fasta_to_tabular" && haveOutput)write("\n")
      }
      out.flush()
    }
  }
  def transform(in:InputStream,out:OutputStream,p:JsonNode):Unit=delegate.transform(in,out,p)
  def inferOutputs(in:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={validate(p);delegate.inferOutputs(in,p)}
  def previewOutputs(in:Map[String,StreamDescriptor],p:JsonNode,l:PreviewLimits):Map[String,OutputSample]={validate(p);delegate.previewOutputs(in,p,l)}
  def process(ctx:OperatorContext):Unit={validate(ctx.params);delegate.process(ctx)}
}
object FastaLength extends SequenceOperator("fasta_length")
object FastaFilterLength extends SequenceOperator("fasta_filter_length")
object FastaToTabular extends SequenceOperator("fasta_to_tabular")
object RemoveBeginning extends SequenceOperator("remove_beginning")
object Head extends SequenceOperator("head")
object Tail extends SequenceOperator("tail")
