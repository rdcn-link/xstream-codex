package xstream.galaxy
import java.io.InputStream
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

abstract class IntervalOperators(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(operation=="group_table"||operation=="expand_columns"||operation=="interval_overlap")"tsv"else if(operation=="bed_genome_coverage")"bedgraph"else"bed"
 override protected def validate(p:JsonNode):Unit={
  super.validate(p)
  if(Set("expand_columns","interval_overlap")(operation)){val a=param(p,"columns").elements.asScala.map(_.asInt).toVector;require(a.nonEmpty&&a.distinct==a,"columns must be nonempty and unique");if(operation=="interval_overlap")require(a.size==4,"four coordinate columns required")else require(a==a.sorted,"expanded columns must be increasing")}
  if(spec.path("parameters").path("properties").has("chromosomes"))chromosomeLengths(p)
 }
 private def chromosomeLengths(p:JsonNode):Vector[(String,Long)]={
  val result=param(p,"chromosomes").elements.asScala.map{v=>val x=v.asText;val at=x.lastIndexOf('=');require(at>0,"chromosome length must be name=length");val name=x.take(at);require(!name.exists(_.isWhitespace),"invalid chromosome name");val length=x.drop(at+1).toLong;require(length>0&&length<=2147483647L,"invalid chromosome length");name->length}.toVector
  require(result.nonEmpty&&result.map(_._1).distinct.size==result.size,"chromosomes must be nonempty and unique");result
 }
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n");def n(k:String)=param(p,k).asInt.toLong;def b(k:String)=param(p,k).asBoolean;def t(k:String)=param(p,k).asText
  val lengths=if(spec.path("parameters").path("properties").has("chromosomes"))chromosomeLengths(p)else Vector.empty;val genome=lengths.toMap
  case class Bed(f:Array[String]){val chrom=f(0);val start=f(1).toLong;val end=f(2).toLong;def copyRange(a:Long,z:Long):String={val x=f.clone();x(1)=a.toString;x(2)=z.toString;x.mkString("\t")}}
  val saved=mutable.ArrayBuffer.empty[Bed];var chars=0L;var previous:Option[Bed]=None;val seen=mutable.HashSet.empty[String]
  var cluster=0L;var unionStart=0L;var unionEnd=0L;var unionChrom=""
  def sorted(v:Bed):Unit={previous.foreach{a=>if(a.chrom==v.chrom)require(a.start<=v.start,"BED must be sorted by start")else require(!seen(v.chrom),"chromosome blocks must be contiguous")};seen+=v.chrom}
  def save(v:Bed,s:String):Unit={chars+=s.length+64;require(chars<=8388608&&saved.size<100000,"interval buffer exceeds 8 MiB characters or 100000 rows");saved+=v}
  def flushUnion():Unit=if(unionChrom.nonEmpty)row(s"$unionChrom\t$unionStart\t$unionEnd")
  var group:Option[String]=None;var sum=BigDecimal(0);var min=BigDecimal(0);var max=BigDecimal(0);var count=0L;val values=mutable.ArrayBuffer.empty[String];var textBudget=0L
  def decimal(v:BigDecimal):String=v.bigDecimal.stripTrailingZeros.toPlainString
  def flushGroup():Unit=group.foreach{g=>val value=t("operation")match{case "sum"=>decimal(sum);case "mean"=>decimal(sum/BigDecimal(count));case "min"=>decimal(min);case "max"=>decimal(max);case "count"=>count.toString;case "collapse"=>values.mkString(",");case "distinct"=>values.distinct.sorted.mkString(",")};row(g+"\t"+value)}
  lines(input){s=>if(s.nonEmpty && !s.startsWith("#") && !s.startsWith("track ") && !s.startsWith("browser ")){
   active();val f=s.split("\t",-1)
   operation match {
    case "expand_columns"=>val cols=param(p,"columns").elements.asScala.map(_.asInt-1).toVector;require(cols.forall(_<f.length),"selected column missing");val lists=cols.map(i=>f(i).split(",",-1));val size=lists.head.length;require(lists.forall(_.length==size)&&size<=10000&&size.toLong*(s.length+1)<=16777216,"unequal lists or excessive expansion");(0 until size).foreach{j=>active();val x=f.clone();cols.zipWithIndex.foreach{case(c,k)=>x(c)=lists(k)(j)};row(x.mkString("\t"))}
    case "interval_overlap"=>val c=param(p,"columns").elements.asScala.map(_.asInt-1).toVector;require(c.forall(_<f.length),"coordinate column missing");val a=c.map(i=>f(i).toLong);require(a(0)>=0&&a(2)>=0&&a(1)>=a(0)&&a(3)>=a(2)&&a.forall(_<=2147483647L),"invalid coordinates");row(s+"\t"+(math.min(a(1),a(3))-math.max(a(0),a(2))))
    case "group_table"=>require(n("group_column")<=f.length&&n("value_column")<=f.length,"group or value column missing");val key=f(n("group_column").toInt-1);val value=f(n("value_column").toInt-1)
     if(!group.contains(key)){flushGroup();group=Some(key);sum=BigDecimal(0);count=0;values.clear();textBudget=0}
     if(Set("sum","mean","min","max")(t("operation"))){require(value.length<=128,"numeric value too long");val x=BigDecimal(value);require(x.scale.abs<=1000,"numeric exponent exceeds budget");sum+=x;if(count==0||x<min)min=x;if(count==0||x>max)max=x}
     if(Set("collapse","distinct")(t("operation"))){textBudget+=value.length+32;require(textBudget<=8388608&&values.size<100000,"group buffer exceeded");values+=value};count=Math.addExact(count,1)
    case _=>require(f.length>=3&&f(0).nonEmpty,"BED3 required");val v=Bed(f);require(v.start>=0&&v.end>v.start&&v.end<=2147483647L,"positive half-open BED interval required")
     if(genome.nonEmpty)require(genome.contains(v.chrom)&&v.end<=genome(v.chrom),"interval outside configured genome")
     operation match {
      case "bed_sort"|"bed_complement"|"bed_genome_coverage"=>save(v,s)
      case "bed_spacing"=>sorted(v);val d=previous.filter(_.chrom==v.chrom).map(a=>if(v.start<a.end)"-1"else(v.start-a.end).toString).getOrElse(".");row(s+"\t"+d);previous=Some(v)
      case "bed_merge"|"bed_cluster"=>sorted(v);val fresh=unionChrom!=v.chrom||v.start>unionEnd+n("distance")
       if(fresh){if(operation=="bed_merge")flushUnion();unionStart=v.start;unionEnd=v.end;unionChrom=v.chrom;cluster+=1}else unionEnd=math.max(unionEnd,v.end)
       if(operation=="bed_cluster")row(s+"\t"+cluster);previous=Some(v)
      case "bed_slop"|"bed_flank"=>val reverse=if(b("stranded")){require(f.length>=6&&Set("+","-")(f(5)),"stranded operations require BED6 +/-");f(5)=="-"}else false
       val left=if(reverse)n("right")else n("left");val right=if(reverse)n("left")else n("right")
       if(operation=="bed_slop")row(v.copyRange(math.max(0,v.start-left),math.min(genome(v.chrom),v.end+right)))
       else{if(left>0&&v.start>0)row(v.copyRange(math.max(0,v.start-left),v.start));if(right>0&&v.end<genome(v.chrom))row(v.copyRange(v.end,math.min(genome(v.chrom),v.end+right)))}
      case _=>throw new IllegalArgumentException("unsupported interval operator")
     }
   }
  }}
  if(operation=="bed_merge")flushUnion()
  if(operation=="group_table")flushGroup()
  if(operation=="bed_sort")saved.sortBy(v=>(v.chrom,v.start)).foreach(v=>{active();row(v.f.mkString("\t"))})
  if(operation=="bed_complement")lengths.foreach{case(chrom,size)=>var at=0L;saved.filter(_.chrom==chrom).sortBy(_.start).foreach{v=>active();if(v.start>at)row(s"$chrom\t$at\t${v.start}");at=math.max(at,v.end)};if(at<size)row(s"$chrom\t$at\t$size")}
  if(operation=="bed_genome_coverage"){
   val events=mutable.Map.empty[String,mutable.Map[Long,Long]]
   saved.foreach{v=>active();val e=events.getOrElseUpdate(v.chrom,mutable.Map.empty);e(v.start)=e.getOrElse(v.start,0L)+1;e(v.end)=e.getOrElse(v.end,0L)-1}
   lengths.foreach{case(chrom,size)=>val e=events.getOrElse(chrom,mutable.Map.empty[Long,Long]);val points=(e.keys.toVector++Vector(0L,size)).distinct.sorted;var depth=0L;var from=0L;var runStart=0L;var runDepth=0L
    def write(a:Long,z:Long,d:Long):Unit=if(z>a&&(d>0||b("include_zero")))row(s"$chrom\t$a\t$z\t$d")
    points.foreach{x=>active();if(x>from&&depth!=runDepth){write(runStart,from,runDepth);runStart=from;runDepth=depth};from=x;depth+=e.getOrElse(x,0L)};write(runStart,size,runDepth)
   }
  }
 }
}
