package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object ProteinProperties {
 val alphabet="ACDEFGHIKLMNPQRSTVWY"
 val mass=alphabet.zip(Vector(71.0788,103.1388,115.0886,129.1155,147.1766,57.0519,137.1411,113.1594,128.1741,113.1594,131.1926,114.1038,97.1167,128.1307,156.1875,87.0782,101.1051,99.1326,186.2132,163.1760)).toMap
 val kd=alphabet.zip(Vector(1.8,2.5,-3.5,-3.5,2.8,-0.4,-3.2,4.5,-3.9,3.8,1.9,-3.5,-1.6,-3.5,-4.5,-0.8,-0.7,4.2,-0.9,-1.3)).toMap
 val eisenberg=alphabet.zip(Vector(.62,.29,-.90,-.74,1.19,.48,-.40,1.38,-1.50,1.06,.64,-.78,.12,-.85,-2.53,-.18,-.05,1.08,.81,.26)).toMap
 val antigenic=alphabet.zip(Vector(1.064,1.412,.866,.851,1.091,.874,1.105,1.152,.930,1.250,.826,.776,1.064,1.015,.873,1.012,.909,1.383,.893,1.161)).toMap
 def charge(counts:Map[Char,Int],ph:Double):Double={
  def positive(pk:Double)=1d/(1+math.pow(10,ph-pk));def negative(pk:Double)=1d/(1+math.pow(10,pk-ph))
  positive(7.5)-negative(3.6)+Vector('H'->6.5,'K'->10.8,'R'->12.5).map{case(c,k)=>counts.getOrElse(c,0)*positive(k)}.sum-Vector('C'->8.5,'D'->3.9,'E'->4.1,'Y'->10.1).map{case(c,k)=>counts.getOrElse(c,0)*negative(k)}.sum
 }
 def pi(counts:Map[Char,Int]):Double={var lo=0d;var hi=14d;var i=0;while(i<80){val mid=(lo+hi)/2;if(charge(counts,mid)>0)lo=mid else hi=mid;i+=1};(lo+hi)/2}
}
abstract class ProteinScience(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(Set("emboss_digest","emboss_backtranseq")(operation))"fasta"else"tsv"
 private def thresholds(p:JsonNode):Map[Char,Int]={val all=param(p,"minimum_counts").elements.asScala.map{v=>val a=v.asText.split("=",-1);require(a.length==2&&a(0).length==1&&ProteinProperties.alphabet.contains(a(0).head)&&a(1).matches("[0-9]{1,7}"),"count must be standard residue=integer");val n=a(1).toInt;require(n>=1&&n<=param(p,"window").asInt,"invalid residue count threshold");a(0).head->n}.toVector;require(all.nonEmpty&&all.map(_._1).distinct.size==all.size,"unique thresholds required");all.toMap}
 override protected def validate(p:JsonNode):Unit={super.validate(p);if(operation=="emboss_oddcomp")thresholds(p)}
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  import ProteinProperties._
  def row(s:String):Unit=emit(s+"\n");def fmt(v:Double):String={require(v.isFinite,"nonfinite property");String.format(Locale.ROOT,"%.6f",Double.box(if(v==0)0d else v))}
  val headings=Map("emboss_pepstats"->"sequence\tlength\tmolecular_weight\tmean_hydropathy\tcharge_ph7\tisoelectric_point\textinction_reduced","emboss_iep"->"sequence\tisoelectric_point","emboss_charge"->"sequence\tph\tcharge","emboss_hmoment"->"sequence\tstart\tend\tmoment","emboss_pepwindow"->"sequence\tstart\tend\tmean_hydropathy","emboss_pepinfo"->"sequence\tposition\tresidue\tresidue_mass\tkyte_doolittle\teisenberg","emboss_antigenic"->"sequence\tcenter\tpropensity","emboss_oddcomp"->"sequence\tstart\tend\twindow");headings.get(operation).foreach(row)
  var title:Option[String]=None;val sequence=new java.lang.StringBuilder;var records=0
  def accept(h:String,raw:String):Unit={active();records+=1;require(records<=100000&&raw.nonEmpty,"nonempty protein and record limit required");val seq=raw.toUpperCase(Locale.ROOT);require(seq.forall(alphabet.contains(_)),"standard 20-residue protein required");val id=h.takeWhile(!_.isWhitespace);val counts=seq.groupMapReduce(identity)(_=>1)(_+_)
   operation match{
    case "emboss_pepstats"=>val mw=seq.iterator.map(mass).sum+18.01528;val hydro=seq.iterator.map(kd).sum/seq.length;val extinction=counts.getOrElse('W',0).toLong*5500+counts.getOrElse('Y',0).toLong*1490;row(s"$id\t${seq.length}\t${fmt(mw)}\t${fmt(hydro)}\t${fmt(charge(counts,7))}\t${fmt(pi(counts))}\t$extinction")
    case "emboss_iep"=>row(id+"\t"+fmt(pi(counts)))
    case "emboss_charge"=>val ph=param(p,"ph_x100").asInt/100d;row(id+"\t"+fmt(ph)+"\t"+fmt(charge(counts,ph)))
    case "emboss_pepinfo"=>require(seq.length<=100000,"residue output exceeds limit");seq.zipWithIndex.foreach{case(c,i)=>active();row(s"$id\t${i+1}\t$c\t${fmt(mass(c))}\t${fmt(kd(c))}\t${fmt(eisenberg(c))}")}
    case "emboss_backtranseq"=>require(seq.length.toLong*3<=8388608,"DNA output exceeds record budget");val codons=Genetics.codons.toVector.filter(_._2!='*').groupBy(_._2).map{case(aa,cs)=>aa->cs.map(_._1).min};val out=new java.lang.StringBuilder;seq.zipWithIndex.foreach{case(c,i)=>if((i&4095)==0)active();out.append(codons(c))};row(">"+h);row(out.toString)
    case "emboss_digest"=>var start=0;var number=0;var i=0;def peptide(end:Int):Unit=if(end>start){number+=1;require(number<=100000,"peptide output budget exceeded");row(s">${id}_peptide_$number start=${start+1} end=$end");row(seq.substring(start,end));start=end};while(i<seq.length){if((i&4095)==0)active();if("KR".contains(seq(i))&&(i+1==seq.length||seq(i+1)!='P'))peptide(i+1);i+=1};peptide(seq.length)
    case "emboss_hmoment"|"emboss_pepwindow"|"emboss_antigenic"|"emboss_oddcomp"=>
     val width=if(operation=="emboss_antigenic")7 else param(p,"window").asInt;val windows=math.max(0,seq.length-width+1);require(windows<=100000,"window output budget exceeded")
     if(operation=="emboss_hmoment")require(windows.toLong*width<=64000000,"moment calculation budget exceeded")
     val thresholds0=if(operation=="emboss_oddcomp")thresholds(p)else Map.empty[Char,Int];val observed=mutable.Map.empty[Char,Int];var sum=0d
     if(windows>0)seq.take(width).foreach{c=>observed(c)=observed.getOrElse(c,0)+1;if(operation=="emboss_pepwindow")sum+=kd(c)else if(operation=="emboss_antigenic")sum+=antigenic(c)}
     (0 until windows).foreach{i=>active();if(i>0){val before=seq(i-1);val after=seq(i+width-1);observed(before)-=1;observed(after)=observed.getOrElse(after,0)+1;if(operation=="emboss_pepwindow")sum+=kd(after)-kd(before)else if(operation=="emboss_antigenic")sum+=antigenic(after)-antigenic(before)}
      operation match{
       case "emboss_hmoment"=>val angle=param(p,"angle").asInt*math.Pi/180;var x=0d;var y=0d;var k=0;while(k<width){x+=eisenberg(seq(i+k))*math.cos(k*angle);y+=eisenberg(seq(i+k))*math.sin(k*angle);k+=1};row(s"$id\t${i+1}\t${i+width}\t${fmt(math.hypot(x,y)/width)}")
       case "emboss_pepwindow"=>row(s"$id\t${i+1}\t${i+width}\t${fmt(sum/width)}")
       case "emboss_antigenic"=>row(s"$id\t${i+4}\t${fmt(sum/7)}")
       case "emboss_oddcomp"=>if(thresholds0.forall{case(c,n)=>observed.getOrElse(c,0)>=n})row(s"$id\t${i+1}\t${i+width}\t${seq.substring(i,i+width)}")
      }
     }
    case _=>throw new IllegalArgumentException("unknown protein operation")
   }
  }
  def flush():Unit=title.foreach(h=>accept(h,sequence.toString))
  lines(input){s=>active();if(s.startsWith(">")){flush();require(s.drop(1).trim.nonEmpty && !s.contains('\t'),"valid protein header required");title=Some(s.drop(1));sequence.setLength(0)}else if(s.nonEmpty){require(title.nonEmpty&&s.forall(c=>alphabet.contains(c.toUpper)),"protein sequence required");require(sequence.length.toLong+s.length<=8388608,"sequence record exceeds 8 MiB characters");sequence.append(s)}};flush()
 }
}
