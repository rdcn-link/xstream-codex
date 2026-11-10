package xstream.galaxy
import java.io._
import java.nio.charset.StandardCharsets.UTF_8
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import xstream.operator.OperatorContext
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}

/** Two bounded bytes streams; SDK inputs are consumed once in lexical port order. */
abstract class PairedStreams(op:String) extends ExtendedOperator(op){
 override def inputPorts:Vector[String]=Vector("reference","source")
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit
 final protected def run(i:InputStream,emit:String=>Unit,p:JsonNode):Unit=throw new IllegalArgumentException("reference and source streams required")
 override def transformInputs(inputs:Map[String,InputStream],output:OutputStream,p:JsonNode):Unit={
  validate(p);active();require(inputs.keySet==inputPorts.toSet,"reference and source required")
  val out=new BufferedWriter(new OutputStreamWriter(output,UTF_8));runPair(inputs("reference"),inputs("source"),s=>{active();require(s.length<=16777216,"output row budget exceeded");out.write(s)},p);active();out.flush()
 }
 override def inferOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={validate(p);require(inputs.keySet==inputPorts.toSet,"reference and source required");require(inputs.values.forall(d=>d.representations.isEmpty||d.representations("bytes")),"bytes inputs required");InferenceResult(Map("result"->StreamDescriptor(Set("bytes"),Set(format),certainty="known")))}
 override def previewOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode,l:PreviewLimits):Map[String,OutputSample]={
  inferOutputs(inputs,p);val samples=inputPorts.map(n=>n->inputs(n).sample.getOrElse(throw new IllegalArgumentException("Upstream sample unavailable: "+n))).toMap;require(samples.values.forall(_.representation=="bytes"),"bytes samples required")
  val data=new ByteArrayOutputStream;var truncated=samples.values.exists(_.truncated);object Full extends RuntimeException
  val output=new OutputStream{def write(b:Int):Unit={if(data.size>=l.maxBytes){truncated=true;throw Full};data.write(b)}}
  try transformInputs(samples.map{case(n,s)=>n->new ByteArrayInputStream(s.bounded(l).data.toArray)},output,p)catch{case Full=>()}
  Map("result"->OutputSample("bytes",format,data=data.toByteArray.toVector,truncated=truncated).bounded(l))
 }
 override def process(c:OperatorContext):Unit={
  validate(c.params);require(c.inputNames==inputPorts,"reference and source required")
  val buffers=inputPorts.map{n=>val in=c.input(n).byteStream();val b=new ByteArrayOutputStream;val chunk=new Array[Byte](8192);try{var count=in.read(chunk);while(count>=0){active();require(b.size.toLong+count<=8388608,"input exceeds 8 MiB bytes");b.write(chunk,0,count);count=in.read(chunk)}}finally in.close();n->new ByteArrayInputStream(b.toByteArray)}.toMap
  transformInputs(buffers,c.bytesOutput("result",format).byteStream(),c.params)
 }
}
abstract class IntervalPairs(op:String) extends PairedStreams(op){
 override protected def format:String=if(Set("bed_intersect","bed_subtract","bed_window")(operation))"bed"else"tsv"
 case class Bed(fields:Vector[String],start:Long,end:Long){def chrom=fields.head;def strand=fields.lift(5).getOrElse(".");def text=fields.mkString("\t")}
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n")
  def read(in:InputStream):Vector[Bed]={val all=mutable.ArrayBuffer.empty[Bed];var chars=0L;lines(in){s=>if(s.nonEmpty && !s.startsWith("#")){active();val f=s.split("\t",-1).toVector;require(f.size>=3&&f(0).nonEmpty && !f(0).exists(_.isWhitespace),"BED3 required");val a=f(1).toLong;val z=f(2).toLong;require(a>=0&&z>a&&z<=2147483647L,"positive half-open BED required");chars+=s.length+64;require(chars<=8388608&&all.size<100000,"BED buffer exceeded");all+=Bed(f,a,z)}};all.toVector}
  val b=read(reference);val a=read(source);require(a.size.toLong*b.size<=16000000,"interval comparison budget exceeded")
  val groups=b.groupBy(_.chrom);var comparisons=0L
  def overlap(x:Bed,y:Bed)=math.max(0L,math.min(x.end,y.end)-math.max(x.start,y.start))
  def candidates(x:Bed):Vector[Bed]=groups.getOrElse(x.chrom,Vector.empty)
  def visit():Unit={comparisons+=1;if((comparisons&1023)==0)active()}
  def fmt(v:Double)=String.format(java.util.Locale.ROOT,"%.6f",Double.box(v))
  def union(v:Vector[(Long,Long)]):Vector[(Long,Long)]={val out=mutable.ArrayBuffer.empty[(Long,Long)];v.sortBy(_._1).foreach{case(s,e)=>active();if(out.nonEmpty&&s<=out.last._2)out(out.size-1)=(out.last._1,math.max(e,out.last._2))else out+=((s,e))};out.toVector}
  if(operation=="bed_jaccard"){
   val groupedA=a.groupBy(_.chrom);val keys=(a.map(_.chrom)++b.map(_.chrom)).distinct;var intersection=0L;var totalUnion=0L
   keys.foreach{chr=>val x=union(groupedA.getOrElse(chr,Vector.empty).map(v=>v.start->v.end));val y=union(groups.getOrElse(chr,Vector.empty).map(v=>v.start->v.end));var i=0;var j=0;while(i<x.size&&j<y.size){active();intersection+=math.max(0L,math.min(x(i)._2,y(j)._2)-math.max(x(i)._1,y(j)._1));if(x(i)._2<y(j)._2)i+=1 else j+=1};totalUnion+=union(x++y).map(v=>v._2-v._1).sum}
   row("intersection\tunion\tjaccard");row(s"$intersection\t$totalUnion\t${fmt(if(totalUnion==0)0d else intersection.toDouble/totalUnion)}")
  }else a.foreach{x=>active();val all=candidates(x);val hits=all.filter{y=>visit();overlap(x,y)>0}
   operation match{
    case "bed_intersect"=>hits.foreach{y=>val start=math.max(x.start,y.start);val end=math.min(x.end,y.end);row((x.fields.updated(1,start.toString).updated(2,end.toString)).mkString("\t"))}
    case "bed_subtract"=>val clipped=union(hits.map(y=>math.max(x.start,y.start)->math.min(x.end,y.end)));var start=x.start;clipped.foreach{case(s,e)=>if(s>start)row(x.fields.updated(1,start.toString).updated(2,s.toString).mkString("\t"));start=math.max(start,e)};if(start<x.end)row(x.fields.updated(1,start.toString).updated(2,x.end.toString).mkString("\t"))
    case "bed_window"=>val window=param(p,"window").asInt.toLong;all.foreach{y=>visit();if(y.end>math.max(0L,x.start-window)&&y.start<x.end+window)row(x.text+"\t"+y.text)}
    case "bed_closest"=>if(all.isEmpty)row(x.text+"\t.\t-1\t-1\t-1")else{def distance(y:Bed):Long=if(overlap(x,y)>0)0L else if(y.start>=x.end)y.start-x.end+1 else x.start-y.end+1;val d=all.map(distance).min;all.filter(y=>distance(y)==d).foreach(y=>row(x.text+"\t"+y.text+"\t"+d))}
    case "bed_coverage"=>val covered=union(hits.map(y=>math.max(x.start,y.start)->math.min(x.end,y.end))).map(v=>v._2-v._1).sum;row(s"${x.text}\t${hits.size}\t$covered\t${x.end-x.start}\t${fmt(covered.toDouble/(x.end-x.start))}")
    case "bed_map"=>val col=param(p,"column").asInt-1;val values=hits.map{y=>require(col<y.fields.size,"mapped column missing");val v=y.fields(col).toDouble;require(v.isFinite&&math.abs(v)<=1e100,"finite bounded number required");v};val result=if(values.isEmpty)"."else{val v=param(p,"operation").asText match{case "sum"=>values.sum;case "mean"=>values.sum/values.size;case "min"=>values.min;case "max"=>values.max};require(v.isFinite,"numeric overflow");fmt(v)};row(x.text+"\t"+result)
    case "bed_annotate"=>val covered=union(hits.map(y=>math.max(x.start,y.start)->math.min(x.end,y.end))).map(v=>v._2-v._1).sum;row(s"${x.text}\t${hits.size}\t${fmt(covered.toDouble/(x.end-x.start))}")
    case _=>throw new IllegalArgumentException("unknown paired interval operation")
   }
  }
 }
}
