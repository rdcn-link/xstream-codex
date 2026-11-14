package xstream.galaxy

import java.io._
import java.nio.charset.{StandardCharsets,CodingErrorAction}
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._
import xstream.operator.{TransformOperator,ByteStreamOperator,OperatorContext}
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}

/** Bounded line-oriented adaptations of Galaxy trimming, interval and VCF tools. */
abstract class GenomicTextOperator(val operation:String) extends TransformOperator {
 private def active():Unit=if(Thread.currentThread.isInterrupted)throw new CancellationException("operator cancelled")
 private def integer(p:JsonNode,key:String,default:Int,max:Int=1000000000):Int={
  val n=p.get(key);require(n==null||n.isIntegralNumber&&n.canConvertToInt,key+" must be an integer")
  val v=if(n==null)default else n.asInt;require(v>=0&&v<=max,key+" outside allowed range");v
 }
 private def text(p:JsonNode,key:String,default:String=""):String={require(!p.has(key)||p.path(key).isTextual,key+" must be text");val s=p.path(key).asText(default);require(s.length<=4096&& !s.exists(c=>c=='\n'||c=='\r'||c=='\t'),key+" invalid or too long");s}
 private def boolean(p:JsonNode,key:String):Boolean={require(!p.has(key)||p.path(key).isBoolean,key+" must be boolean");p.path(key).asBoolean(false)}
 private def tags(p:JsonNode,key:String):Set[String]={val s=text(p,key);if(s.isEmpty)Set.empty else{val a=s.split(",",-1);require(a.length<=128&&a.forall(_.matches("[A-Za-z_][A-Za-z0-9_.]*")),"invalid INFO tags");a.toSet}}
 private def validate(p:JsonNode):Unit={
  require(p!=null&&p.isObject,"parameters must be an object")
  val keys=operation match{
   case "trim"=>Set("column","start","end","ignore_prefixes","fastq")
   case "make_windows"=>Set("window_size","offset","start_column","end_column")
   case _=>Set("reference_sequence","region","quality","quality_operator","keep_info","discard_info","pass_only")
  };require(p.fieldNames.asScala.forall(keys),"unknown parameter")
  operation match{
   case "trim"=>integer(p,"column",0,65536);val start=integer(p,"start",1,1048576);val end=integer(p,"end",0,1048576);require(start>=1&&(end==0||end>=start),"start must be positive and end zero or at least start");text(p,"ignore_prefixes");boolean(p,"fastq")
   case "make_windows"=>require(integer(p,"window_size",500)>0,"window_size must be positive");integer(p,"offset",0);val start=integer(p,"start_column",2,65536);val end=integer(p,"end_column",3,65536);require(start>0&&end>0&&start!=end,"coordinate columns must be positive and distinct")
   case _=>val ref=text(p,"reference_sequence");val region=text(p,"region");require(ref.isEmpty||region.isEmpty,"choose reference_sequence or region, not both");require(!ref.exists(_.isWhitespace),"reference sequence cannot contain whitespace")
    if(region.nonEmpty){require(region.matches("[^:\\s]+:[0-9]+\\.\\.[0-9]+"),"region must be reference:start..end");val bounds=region.split(":",2)(1).split("\\.\\.");val a=bounds(0).toLong;val b=bounds(1).toLong;require(a>0&&b>=a,"region coordinates must be positive and ordered")}
    val logic=text(p,"quality_operator","none");require(Set("none","eq","le","lt","ge","gt")(logic),"unknown quality_operator")
    require(!p.has("quality")||p.path("quality").isNumber,"quality must be numeric");val quality=p.path("quality").asDouble(0);require(java.lang.Double.isFinite(quality)&&quality>=0,"quality must be finite and nonnegative")
    require(tags(p,"keep_info").isEmpty||tags(p,"discard_info").isEmpty,"keep_info and discard_info are mutually exclusive");boolean(p,"pass_only")
  }
 }
 private val delegate=new ByteStreamOperator {
  override def outputFormat:String=operation match{case "vcf_extract"=>"vcf";case "make_windows"=>"bed";case _=>"text"}
  def transform(input:InputStream,output:OutputStream,p:JsonNode):Unit={
   validate(p);active()
   val reader=new PushbackReader(new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder.onMalformedInput(CodingErrorAction.REPORT)),8192),1)
   val out=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),8192)
   def emit(s:String):Unit={active();require(s.length<=2097152,"output row exceeds 2 MiB characters");out.write(s);out.write("\n")}
   val region=if(operation=="vcf_extract")text(p,"region")else""
   val bounds=if(region.nonEmpty){val a=region.split(":",2);val b=a(1).split("\\.\\.");Some((a(0),b(0).toLong,b(1).toLong))}else None
   val keep=if(operation=="vcf_extract")tags(p,"keep_info")else Set.empty[String]
   val discard=if(operation=="vcf_extract")tags(p,"discard_info")else Set.empty[String]
   var index=0L
   def row(line:String):Unit={
    active();val current=index;index=Math.addExact(index,1)
    operation match{
     case "trim"=>if(line.nonEmpty){
      val ignored=(boolean(p,"fastq")&&current%2==0)||text(p,"ignore_prefixes").codePoints().toArray.contains(line.codePointAt(0))
      def trimmed(s:String):String={val count=s.codePointCount(0,s.length);val from=math.min(count,integer(p,"start",1)-1);val end=integer(p,"end",0);val until=if(end==0)count else math.min(count,end);s.substring(s.offsetByCodePoints(0,from),s.offsetByCodePoints(0,until))}
      if(ignored)emit(line)else {val col=integer(p,"column",0,65536);if(col==0)emit(trimmed(line))else{val f=line.split("\t",-1);require(col<=f.length,"selected column does not exist");f(col-1)=trimmed(f(col-1));emit(f.mkString("\t"))}}
     }
     case "make_windows"=>if(line.strip().nonEmpty && !line.strip().startsWith("#")){
      val f=line.strip().split("\t",-1);val a=integer(p,"start_column",2,65536)-1;val b=integer(p,"end_column",3,65536)-1
      require(math.max(a,b)<f.length,"coordinate column does not exist")
      val start=f(a).toLong;val end=f(b).toLong;require(start>=0&&end>=start,"interval must have nonnegative ordered coordinates")
      val size=integer(p,"window_size",500).toLong;val offset=integer(p,"offset",0);val stride=if(offset==0)size else offset.toLong
      val count=if(end-start<size)0L else (end-start-size)/stride+1
      require(count<=100000 && count*(line.length.toLong+40)*2<=67108864,"interval window expansion exceeds budget")
      var i=0L;var pos=start
      while(i<count){active();f(a)=pos.toString;f(b)=Math.addExact(pos,size).toString;emit(f.mkString("\t"));i+=1;if(i<count)pos=Math.addExact(pos,stride)}
     }
     case "vcf_extract"=>if(line.startsWith("#"))emit(line)else if(line.nonEmpty){
      val f=line.split("\t",-1);require(f.length>=8,"VCF record requires at least eight fields");val pos=f(1).toLong;require(pos>0,"VCF position must be positive")
      val ref=text(p,"reference_sequence");var accept=(ref.isEmpty||f(0)==ref)&&bounds.forall{case(chrom,a,b)=>f(0)==chrom&&pos>=a&&pos<=b}
      if(accept && boolean(p,"pass_only"))accept=f(6)=="PASS"
      if(accept&&(keep.nonEmpty||discard.nonEmpty)){val present=f(7).split(";",-1).filter(_!=".").map(_.takeWhile(_!='=')).toSet;accept=(keep.isEmpty||keep.exists(present))&& !discard.exists(present)}
      val logic=text(p,"quality_operator","none")
      if(accept&&logic!="none"){
       if(f(5)==".")accept=false else{val q=f(5).toDouble;require(java.lang.Double.isFinite(q)&&q>=0,"invalid VCF quality");val target=p.path("quality").asDouble(0);accept=logic match{case "eq"=>q==target;case "le"=>q<=target;case "lt"=>q<target;case "ge"=>q>=target;case "gt"=>q>target}}
      }
      if(accept)emit(line)
     }
    }
   }
   val line=new java.lang.StringBuilder();var ch=reader.read();var seen=0
   while(ch != -1){if((seen & 4095)==0)active();seen+=1
    if(ch=='\r'||ch=='\n'){if(ch=='\r'){val next=reader.read();if(next != -1&&next!='\n')reader.unread(next)};row(line.toString);line.setLength(0)}
    else{require(line.length<1048576,"line exceeds 1 MiB characters");line.append(ch.toChar)};ch=reader.read()
   };if(line.length>0)row(line.toString);active();out.flush()
  }
 }
 def transform(in:InputStream,out:OutputStream,p:JsonNode):Unit=delegate.transform(in,out,p)
 def inferOutputs(in:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={validate(p);delegate.inferOutputs(in,p)}
 def previewOutputs(in:Map[String,StreamDescriptor],p:JsonNode,l:PreviewLimits):Map[String,OutputSample]={validate(p);delegate.previewOutputs(in,p,l)}
 def process(ctx:OperatorContext):Unit={validate(ctx.params);delegate.process(ctx)}
}
object Trim extends GenomicTextOperator("trim")
object MakeWindows extends GenomicTextOperator("make_windows")
object VcfExtract extends GenomicTextOperator("vcf_extract")
