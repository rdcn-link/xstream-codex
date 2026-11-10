package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable

abstract class FastqOperators(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(Set("fastq_to_tabular","fastq_quality_stats")(operation))"tsv"else"fastq"
 override protected def validate(p:JsonNode):Unit={super.validate(p);if(operation=="fastq_filter_quality"){
  val max=param(p,"maximum_length").asInt;require(max==0||max>=param(p,"minimum_length").asInt,"maximum length below minimum")
  val q=param(p,"maximum_quality").asInt;require(q==0||q>=param(p,"minimum_quality").asInt,"maximum quality below minimum")
 }}
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def n(k:String)=param(p,k).asInt;def t(k:String)=param(p,k).asText;def row(s:String):Unit=emit(s+"\n")
  case class Read(title:String,seq:String,qual:String){def write():Unit={row("@"+title);row(seq);row("+");row(qual)};def id:String=title.takeWhile(!_.isWhitespace)}
  def valid(r:Read,encoding:String="sanger"):Unit={require(r.title.nonEmpty&&r.title.head!='@',"FASTQ identifier required without @ prefix");require(r.seq.nonEmpty&&r.seq.length==r.qual.length,"sequence and quality lengths must match and be positive");require(r.seq.forall(c=>Genetics.iupac.contains(c.toUpper)),"invalid nucleotide");val low=if(encoding=="solexa")59 else if(encoding=="illumina")64 else 33;require(r.qual.forall(c=>c>=low&&c<=126),"quality outside encoding range")}
  var pending:Option[Read]=None;val stored=mutable.ArrayBuffer.empty[Read];val ids=mutable.HashSet.empty[String];var storedChars=0L
  val quality=mutable.ArrayBuffer.empty[Array[Long]];val bases=mutable.ArrayBuffer.empty[Array[Long]]
  def fmt(x:Double):String=String.format(Locale.ROOT,"%.6f",Double.box(x))
  def pair(r:Read):(String,Int)={val id=r.id;require(id.endsWith("/1")||id.endsWith("/2"),"slash /1 or /2 mate ID required");id.dropRight(2)->(id.last-'0')}
  def accept(r:Read):Unit={
   active();valid(r,if(operation=="fastq_groom")t("input_encoding")else"sanger")
   operation match{
    case "tabular_to_fastq"=>r.write()
    case "fastq_to_tabular"=>val title=r.title.replace('\t',' ');val fields=if(n("description_columns")==1)Vector(title)else title.trim.split("\\s+",n("description_columns")).toVector.padTo(n("description_columns"),"");row((fields++Vector(r.seq,r.qual)).mkString("\t"))
    case "fastq_mask_quality"=>val seq=r.seq.zip(r.qual).map{case(c,q)=>val x=q-33;val yes=t("comparison")match{case "lt"=>x<n("threshold");case "le"=>x<=n("threshold");case "eq"=>x==n("threshold");case "ne"=>x!=n("threshold");case "ge"=>x>=n("threshold");case "gt"=>x>n("threshold")};if(yes){if(t("mask")=="N")'N'else c.toLower}else c}.mkString;r.copy(seq=seq).write()
    case "fastq_filter_quality"=>val bad=r.qual.count(q=>q-33<n("minimum_quality")||(n("maximum_quality")>0&&q-33>n("maximum_quality")));if(r.seq.length>=n("minimum_length")&&(n("maximum_length")==0||r.seq.length<=n("maximum_length"))&&bad<=n("deviants"))r.write()
    case "fastq_join_mates"=>pair(r);pending match{case None=>pending=Some(r);case Some(a)=>val x=pair(a);val y=pair(r);require(x._1==y._1&&x._2!=y._2,"adjacent records must be mates");val(first,second)=if(x._2==1)(a,r)else(r,a);first.copy(seq=first.seq+second.seq,qual=first.qual+second.qual).write();pending=None}
    case "fastq_deinterlace"=>pair(r);storedChars+=r.title.length+r.seq.length+r.qual.length+64;require(storedChars<=8388608&&stored.size<100000&&ids.add(r.id),"mate buffer exceeded or duplicate read ID");stored+=r
    case "fastq_groom"=>val from=t("input_encoding");val to=t("output_encoding");val encoded=r.qual.map{c=>
     val raw=c-(if(from=="sanger")33 else 64);val phred=if(from=="solexa")10*math.log10(1+math.pow(10,raw/10d))else raw.toDouble
     val converted=if(to=="solexa"){if(phred<=0)-5d else 10*math.log10(math.pow(10,phred/10d)-1)}else phred
     val minimum=if(to=="solexa")-5 else 0;val maximum=if(to=="sanger")93 else 62;val offset=if(to=="sanger")33 else 64
     (math.max(minimum,math.min(maximum,math.round(converted).toInt))+offset).toChar};r.copy(qual=encoded).write()
    case "fastq_quality_stats"=>require(r.seq.length<=10000,"quality statistics limited to 10000 positions");while(quality.size<r.seq.length){quality+=Array.fill(94)(0L);bases+=Array.fill(6)(0L)}
     r.seq.indices.foreach{i=>if((i&4095)==0)active();val q=r.qual(i)-33;quality(i)(q)=Math.addExact(quality(i)(q),1L);val k="ACGTN".indexOf(r.seq(i).toUpper);val j=if(k<0)5 else k;bases(i)(j)=Math.addExact(bases(i)(j),1L)}
    case _=>throw new IllegalArgumentException("unsupported FASTQ operation")
   }
  }
  var block=Vector.empty[String]
  lines(input){line=>if(operation=="tabular_to_fastq"){
   val f=line.split("\t",-1);val cols=Vector(n("identifier_column"),n("sequence_column"),n("quality_column"));require(cols.forall(_<=f.length),"selected column missing");accept(Read(f(cols(0)-1),f(cols(1)-1),f(cols(2)-1)))
  }else{
   block=block:+line
   if(block.size==4){require(block(0).startsWith("@")&&block(2).startsWith("+"),"four-line FASTQ required");require(block(2)=="+"||block(2).drop(1)==block(0).drop(1),"FASTQ plus title mismatch");accept(Read(block(0).drop(1),block(1),block(3)));block=Vector.empty}
  }}
  require(block.isEmpty,"truncated FASTQ record");require(pending.isEmpty,"unpaired final read")
  if(operation=="fastq_deinterlace"){
   val lookup=stored.map(r=>r.id->r).toMap;val visited=mutable.HashSet.empty[String]
   stored.foreach{r=>active();if(!visited(r.id)){val(base,mate)=pair(r);val other=base+"/"+(3-mate);visited+=r.id;val selection=t("selection");lookup.get(other)match{
    case Some(m)=>visited+=m.id;val chosen=if(selection.endsWith("1")){if(mate==1)r else m}else{if(mate==2)r else m};if(selection.startsWith("paired"))chosen.write()
    case None=>if(selection==s"single$mate")r.write()
   }}}
  }
  if(operation=="fastq_quality_stats"){
   row("position\tcount\tmin\tmax\tmean\tQ1\tmedian\tQ3\tA\tC\tG\tT\tN\tother")
   quality.indices.foreach{i=>active();val h=quality(i);val total=h.sum;val min=h.indexWhere(_>0);val max=h.lastIndexWhere(_>0);val sum=h.indices.map(j=>h(j)*j.toDouble).sum
    def at(rank:Long):Double={var c=0L;var j=0;while(j<h.length&&c+h(j)<=rank){c+=h(j);j+=1};j.toDouble}
    def percentile(f:Double):String={val rank=(total-1)*f;val low=math.floor(rank).toLong;val high=math.ceil(rank).toLong;fmt(at(low)+(at(high)-at(low))*(rank-low))}
    row((Vector((i+1).toString,total.toString,min.toString,max.toString,fmt(sum/total),percentile(.25),percentile(.5),percentile(.75))++bases(i).map(_.toString)).mkString("\t"))
   }
  }
 }
}
