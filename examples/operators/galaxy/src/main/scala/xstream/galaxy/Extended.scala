package xstream.galaxy

import java.io._
import java.nio.charset.{StandardCharsets,CodingErrorAction}
import java.util.Locale
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.{JsonNode,ObjectMapper}
import scala.jdk.CollectionConverters._
import xstream.operator.{Operator,ByteStreamOperator,OperatorContext}
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}

/** Common stream and resource contracts for independently documented Galaxy adapters. */
abstract class ExtendedOperator(val operation:String) extends Operator {
 protected def active():Unit=if(Thread.currentThread.isInterrupted)throw new CancellationException("operator cancelled")
 protected def spec:JsonNode=ExtendedContract.specs.path(operation)
 protected def validate(p:JsonNode):Unit={
  require(p!=null&&p.isObject,"parameters must be an object")
  val props=spec.path("parameters").path("properties")
  require(p.fieldNames.asScala.forall(props.has),"unknown parameter")
  def check(v:JsonNode,s:JsonNode):Unit={s.path("type").asText match{
   case "string"=>require(v.isTextual&&v.asText.length<=s.path("maxLength").asInt(4096)&& !v.asText.exists(c=>c=='\r'||c=='\n'||c==0),"invalid text parameter");if(s.has("enum"))require(s.path("enum").elements.asScala.exists(_==v),"unsupported parameter value")
   case "boolean"=>require(v.isBoolean,"parameter must be boolean")
   case "integer"=>require(v.isIntegralNumber&&v.canConvertToInt&&v.asInt>=s.path("minimum").asInt&&v.asInt<=s.path("maximum").asInt,"integer parameter outside allowed bounds")
   case "array"=>require(v.isArray&&v.size<=s.path("maxItems").asInt(128),"invalid parameter array");v.elements.asScala.foreach(check(_,s.path("items")))
  }}
  p.fields.asScala.foreach(e=>check(e.getValue,props.path(e.getKey)))
  def text(k:String)=param(p,k).asText
  if(Set("replace_text_in_line","replace_text_in_column","find_and_replace")(operation))require(text("find").nonEmpty,"find must not be empty")
  if(operation=="unfold_column")require(text("separator").nonEmpty,"separator must not be empty")
  if(Set("arrange_columns","remove_columns_by_header")(operation)){val a=param(p,"names").elements.asScala.map(_.asText).toVector;require(a.nonEmpty&&a.distinct.size==a.size&&a.forall(x=>x.nonEmpty && !x.contains('\t')),"names must be nonempty and unique")}
  if(operation=="fixed_width_to_tabular")require(param(p,"widths").size>0&&param(p,"widths").elements.asScala.map(_.asInt.toLong).sum<=1048576,"widths must be nonempty and fit line budget")
  if(operation=="find_subsequences")require(text("pattern").nonEmpty&&text("pattern").toUpperCase(Locale.ROOT).forall(Genetics.iupac.contains),"invalid IUPAC motif")
  if(operation=="motif_flank_counts"){val a=param(p,"motifs").elements.asScala.map(_.asText).toVector;require(a.nonEmpty&&a.distinct.size==a.size,"motifs must be nonempty and unique");a.foreach{s=>val v=s.split("=",-1);require(v.length<=2&&v.forall(_.nonEmpty)&&v.last.length<=128&&v.last.toUpperCase(Locale.ROOT).forall("ACGTN".contains(_)),"motifs must be [name=]literal DNA")}}
  if(operation=="recurring_lines")require((text("text").length.toLong+1)*param(p,"times").asInt<=16777216,"repeat output exceeds 16 MiB characters")
  if(operation=="vcf_to_pgsnp")require(param(p,"sample_column").asInt==0||param(p,"sample_column").asInt>=10,"VCF sample_column must be zero or at least 10")
 }
 protected def param(p:JsonNode,k:String):JsonNode=Option(p.get(k)).getOrElse(spec.path("parameters").path("properties").path(k).path("default"))
 protected def lines(input:InputStream)(f:String=>Unit):Unit={
  val r=new PushbackReader(new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder.onMalformedInput(CodingErrorAction.REPORT))),1)
  val b=new java.lang.StringBuilder;var c=r.read();var ticks=0
  while(c != -1){if((ticks&4095)==0)active();ticks+=1
   if(c=='\n'||c=='\r'){if(c=='\r'){val n=r.read();if(n != -1&&n!='\n')r.unread(n)};f(b.toString);b.setLength(0)}
   else{require(b.length<1048576,"line exceeds 1 MiB characters");b.append(c.toChar)};c=r.read()
  };if(b.length>0)f(b.toString);active()
 }
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit
 protected def format:String="text"
 private val delegate=new ByteStreamOperator {
  override def outputFormat:String=ExtendedOperator.this.format
  def transform(i:InputStream,o:OutputStream,p:JsonNode):Unit={validate(p);active();val out=new BufferedWriter(new OutputStreamWriter(o,StandardCharsets.UTF_8));run(i,s=>{active();require(s.length<=16777216,"output exceeds row budget");out.write(s)},p);active();out.flush()}
 }
 def transform(i:InputStream,o:OutputStream,p:JsonNode):Unit=delegate.transform(i,o,p)
 def inferOutputs(i:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={validate(p);delegate.inferOutputs(i,p)}
 def previewOutputs(i:Map[String,StreamDescriptor],p:JsonNode,l:PreviewLimits):Map[String,OutputSample]={validate(p);delegate.previewOutputs(i,p,l)}
 def process(c:OperatorContext):Unit={validate(c.params);delegate.process(c)}
}

abstract class ExtendedText(op:String) extends ExtendedOperator(op){
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def t(k:String)=param(p,k).asText;def n(k:String)=param(p,k).asInt;def b(k:String)=param(p,k).asBoolean
  def row(s:String):Unit=emit(s+"\n")
  def fields(s:String)=s.split("\t",-1)
  var index=0L;var number=n("start").toLong
  val stored=scala.collection.mutable.ArrayBuffer.empty[String];var chars=0L
  def store(s:String):Unit={chars+=s.length.toLong+32;require(chars<=8388608&&stored.size<100000,"operation buffer exceeds 8 MiB characters or 100000 rows");stored+=s}
  var previous:Option[String]=None;var count=0L
  def fold(s:String)=if(b("ignore_case"))s.toUpperCase(Locale.ROOT)else s
  def key(s:String):String={val col=n("column");fold(if(col==0)s else {val f=fields(s);require(col<=f.length,"selected column missing");f(col-1)})}
  def compare(a:String,c:String):Int={active();val x=key(a);val y=key(c);if(b("numeric")){val u=BigDecimal(x);val v=BigDecimal(y);u.compare(v)}else x.compareTo(y)}
  def flushGroup():Unit=previous.foreach{s=>if(t("mode")=="all"||t("mode")=="repeated"&&count>1||t("mode")=="single"&&count==1)row((if(b("count"))count+"\t"else"")+s)}
  var columns=Vector.empty[Int];var width=0
  def replace(s:String):String={
   val needle=t("find");val replacement=t("replacement");val out=new java.lang.StringBuilder;var pos=0;var replacements=0
   def locate(from:Int):Int={if(!b("ignore_case"))s.indexOf(needle,from)else{var j=from;while(j<=s.length-needle.length && !s.regionMatches(true,j,needle,0,needle.length)){if((j&4095)==0)active();j+=1};if(j<=s.length-needle.length)j else -1}}
   var at=locate(pos)
   while(at>=0){active();out.append(s,pos,at);require(out.length.toLong+replacement.length<=16777216,"replacement expansion exceeds output budget");out.append(replacement);pos=at+needle.length;replacements+=1;at=if(b("first_only")) -1 else locate(pos)}
   require(out.length.toLong+s.length-pos<=16777216,"replacement output exceeds budget");out.append(s,pos,s.length);out.toString
  }
  if(operation=="add_line"&&t("position")=="header")row(t("text"))
  lines(input){s=>
   active();index+=1
   operation match{
    case "reverse_lines"=>store(s)
    case "number_lines"=>if(s.isEmpty)row(" "*n("width"))else{val v=number.toString;val padding=" "*math.max(0,n("width")-v.length);val label=t("style")match{case "ln"=>v+padding;case "rz"=>("0"*math.max(0,n("width")-v.length))+v;case _=>padding+v};row(label+"\t"+s);number=Math.addExact(number,n("increment").toLong)}
    case "sort_lines"|"unique_lines"=>if(index<=n("header"))row(s)else {if(b("numeric"))BigDecimal(key(s));store(s)}
    case "sort_fields"=>row(s.strip().split("\t",-1).sorted.mkString("\t"))
    case "unique_consecutive"=>if(previous.exists(x=>fold(x)==fold(s)))count+=1 else{flushGroup();previous=Some(s);count=1}
    case "unfold_column"=>val f=fields(s);val col=n("column")-1;require(col<f.length,"selected column missing");val a=f(col).split(java.util.regex.Pattern.quote(t("separator")),-1);require(a.length<=10000&&a.length.toLong*(s.length+1)<=16777216,"unfold expansion exceeds budget");a.foreach{x=>active();f(col)=x;row(f.mkString("\t"))}
    case "replace_text_in_column"=>if(b("skip_header")&&index==1)row(s)else{val f=fields(s);val col=n("column")-1;require(col<f.length,"selected column missing");f(col)=replace(f(col));row(f.mkString("\t"))}
    case "replace_text_in_line"|"find_and_replace"=>row(if(b("skip_header")&&index==1)s else replace(s))
    case "grep_lines"=>val found=if(b("ignore_case"))s.toUpperCase(Locale.ROOT).contains(t("pattern").toUpperCase(Locale.ROOT))else s.contains(t("pattern"));if(found != b("invert"))row(s)
    case "arrange_columns"|"remove_columns_by_header"|"sort_columns_by_header"=>
     val f=fields(s)
     if(index==1){require(f.distinct.length==f.length,"header values must be unique");width=f.length
      if(operation=="sort_columns_by_header"){val fixed=n("key_column")-1;require(fixed<f.length,"key column missing");columns=(if(fixed>=0)Vector(fixed)else Vector.empty)++f.indices.filter(_!=fixed).sortBy(f(_))}
      else{val requested=param(p,"names").elements.asScala.map(_.asText).toVector;val names=f.zipWithIndex.map{case(v,i)=>if(i==0&&operation=="remove_columns_by_header")v.dropWhile(t("strip_prefix").contains(_))else v};require(requested.forall(names.contains),"unknown requested header")
       if(operation=="arrange_columns"){val chosen=requested.map(x=>names.indexOf(x));columns=chosen++(if(b("discard"))Vector.empty else f.indices.filterNot(chosen.contains))}
       else columns=f.indices.filter(i=>requested.contains(names(i))==b("keep")).toVector
      }
     };require(f.length==width,"ragged table row");row(columns.map(f(_)).mkString("\t"))
    case "add_input_name"=>val value=if(index==1&&t("header").nonEmpty)t("header")else t("label");row(if(b("prepend"))value+"\t"+s else s+"\t"+value)
    case "fixed_width_to_tabular"=>val sizes=param(p,"widths").elements.asScala.map(_.asInt).toVector;val total=sizes.sum;require(s.length<=total&&(s.length==total||b("pad_short")),"fixed-width row has wrong length");val padded=s.padTo(total,' ');var pos=0;row(sizes.map{len=>val value=padded.substring(pos,pos+len).strip();pos+=len;value}.mkString("\t"))
    case "add_line"=>row(s)
    case "recurring_lines"=>()
   }
  }
  operation match{
   case "reverse_lines"=>stored.reverseIterator.foreach(row)
   case "sort_lines"=>val sorted=stored.sortWith((a,c)=>if(b("reverse"))compare(a,c)>0 else compare(a,c)<0);sorted.foreach(row)
   case "unique_lines"=>var last:Option[String]=None;stored.sortWith((a,c)=>compare(a,c)<0).foreach{s=>if(last.forall(x=>compare(x,s)!=0)){row(s);last=Some(s)}}
   case "unique_consecutive"=>flushGroup()
   case "add_line"=>if(t("position")=="footer")row(t("text"))
   case "recurring_lines"=>(0 until n("times")).foreach{_=>active();row(t("text"))}
   case _=>()
  }
 }
}
