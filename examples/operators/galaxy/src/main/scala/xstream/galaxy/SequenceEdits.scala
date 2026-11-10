package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

abstract class SequenceEdits(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(Set("emboss_checktrans","emboss_textsearch","emboss_fuzzpro","emboss_compseq","emboss_freak")(operation))"tsv"else"fasta"
 private def regions(p:JsonNode):Vector[(Int,Int)]={val values=param(p,"regions").elements.asScala.map(_.asText).toVector;require(values.nonEmpty,"regions required");values.map{s=>require(s.matches("[0-9]{1,7}-[0-9]{1,7}"),"regions must be start-end");val v=s.split("-").map(_.toInt);require(v(0)>=1&&v(1)>=v(0)&&v(1)<=8388608,"invalid coordinate range");v(0)-1->v(1)}}
 override protected def validate(p:JsonNode):Unit={super.validate(p)
  def t(k:String)=param(p,k).asText
  if(Set("emboss_extractseq","emboss_maskseq")(operation))regions(p)
  if(operation=="emboss_cutseq")require(param(p,"end").asInt>=param(p,"start").asInt,"end before start")
  if(operation=="emboss_splitter")require(param(p,"overlap").asInt<param(p,"size").asInt,"overlap must be smaller than window")
  if(operation=="emboss_biosed")require(t("target").nonEmpty&&Vector(t("target"),t("replacement")).forall(_.forall(c=>c.isLetter||c=='*'||c=='-'||c=='.')),"invalid sequence motif")
  if(operation=="emboss_textsearch")require(t("pattern").nonEmpty,"pattern required")
  if(operation=="emboss_descseq")require(!t("description").contains('\t'),"description cannot contain tabs")
  if(operation=="emboss_fuzzpro")require(t("pattern").nonEmpty&&t("pattern").length<=128&&t("pattern").toUpperCase(Locale.ROOT).forall("ACDEFGHIKLMNPQRSTVWYX".contains(_))&&param(p,"mismatches").asInt<=t("pattern").length,"invalid protein motif")
  if(operation=="emboss_freak"){val t0=t("residues");require(t0.nonEmpty&&t0.forall("ACGT".contains(_))&&t0.distinct==t0,"unique uppercase ACGT residues required")}
 }
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n");def t(k:String)=param(p,k).asText;def n(k:String)=param(p,k).asInt;def b(k:String)=param(p,k).asBoolean
  def fmt(v:Double)=String.format(Locale.ROOT,"%.6f",Double.box(v))
  def output(title:String,seq:String):Unit={require(seq.length<=8388608,"output sequence exceeds 8 MiB characters");row(">"+title);row(seq)}
  def dna(s:String):Unit=require(s.forall(c=>Genetics.iupac.contains(c.toUpper)),"nucleotide sequence required")
  def translate(s:String,frame:Int):String=s.drop(frame).grouped(3).filter(_.length==3).map(c=>Genetics.codons.getOrElse(c,'X')).mkString
  var title:Option[String]=None;val sequence=new java.lang.StringBuilder;var ordinal=0;var selected=false;val rng=new java.util.Random(if(operation=="emboss_shuffleseq")n("seed").toLong else 0L)
  val headings=Map("emboss_checktrans"->"sequence\tlength\tstop_count\tlast_stop","emboss_fuzzpro"->"sequence\tstart\tend\tmismatches\tmatch","emboss_compseq"->"sequence\tword\tcount\tfraction","emboss_freak"->"sequence\tstart\tend\tfraction");headings.get(operation).foreach(row)
  def accept(h:String,seq:String):Unit={active();ordinal+=1;require(ordinal<=100000,"sequence record limit exceeded");val id=h.takeWhile(!_.isWhitespace);val upper=seq.toUpperCase(Locale.ROOT)
   operation match{
    case "emboss_biosed"=>val target=t("target").toUpperCase(Locale.ROOT);val repl=t("replacement");val out=new java.lang.StringBuilder;var at=0;var next=upper.indexOf(target,at);while(next>=0){active();require(out.length.toLong+next-at+repl.length<=8388608,"replacement expansion exceeded");out.append(seq,at,next).append(repl);at=next+target.length;next=upper.indexOf(target,at)};require(out.length.toLong+seq.length-at<=8388608,"replacement expansion exceeded");out.append(seq,at,seq.length);output(h,out.toString)
    case "emboss_checktrans"=>row(s"$id\t${seq.length}\t${seq.count(_=='*')}\t${seq.lastIndexOf('*')+1}")
    case "emboss_cutseq"=>require(n("end")<=seq.length,"cut outside sequence");output(h,seq.take(n("start")-1)+seq.drop(n("end")))
    case "emboss_degapseq"=>output(h,seq.filterNot(c=>c=='-'||c=='.'))
    case "emboss_descseq"=>val desc=h.drop(id.length).trim;val merged=if(b("append"))Vector(desc,t("description")).filter(_.nonEmpty).mkString(" ")else t("description");output(id+(if(merged.nonEmpty)" "+merged else""),seq)
    case "emboss_extractseq"=>val ranges=regions(p);require(ranges.forall(_._2<=seq.length)&&ranges.map(x=>(x._2-x._1).toLong).sum<=8388608,"extracted region outside sequence or budget");output(h,ranges.map{case(a,z)=>seq.substring(a,z)}.mkString)
    case "emboss_maskseq"=>val ranges=regions(p);require(ranges.forall(_._2<=seq.length),"mask outside sequence");val events=ranges.flatMap{case(a,z)=>Vector(a->1,z-> -1)}.groupMapReduce(_._1)(_._2)(_+_).toVector.sortBy(_._1);val out=seq.toCharArray;var count=0;var from=0;events.foreach{case(at,d)=>active();if(count>0){var i=from;while(i<at){if((i&4095)==0)active();out(i)=if(b("lowercase"))out(i).toLower else'N';i+=1}};count+=d;from=at};output(h,new String(out))
    case "emboss_notseq"=>if(!param(p,"exclude").elements.asScala.exists(_.asText==id))output(h,seq)
    case "emboss_nthseq"=>if(ordinal==n("number")){selected=true;output(h,seq)}
    case "emboss_skipseq"=>if(ordinal>n("skip"))output(h,seq)
    case "emboss_shuffleseq"=>require((seq.length.toLong+h.length+64)*n("copies")<=16777216,"shuffle output budget exceeded");(1 to n("copies")).foreach{copy=>active();val out=seq.toCharArray;var i=out.length-1;while(i>0){if((i&4095)==0)active();val j=rng.nextInt(i+1);val temp=out(i);out(i)=out(j);out(j)=temp;i-=1};output(s"${id}_shuffle_$copy",new String(out))}
    case "emboss_splitter"=>val step=n("size")-n("overlap");val windows=if(seq.isEmpty)0L else(seq.length.toLong+step-1)/step;require(windows<=100000&&windows*(n("size").toLong+id.length+64)<=16777216,"split output budget exceeded");var at=0;while(at<seq.length){active();val end=math.min(seq.length.toLong,at.toLong+n("size")).toInt;output(s"${id}_${at+1}-$end",seq.substring(at,end));at=if(end==seq.length)seq.length else at+step}
    case "emboss_textsearch"=>val found=if(b("ignore_case"))h.toUpperCase(Locale.ROOT).contains(t("pattern").toUpperCase(Locale.ROOT))else h.contains(t("pattern"));if(found)row(id+"\t"+h.drop(id.length).trim)
    case "emboss_trimest"=>dna(seq);val end=seq.lastIndexWhere(c=>c!='A'&&c!='a')+1;output(h,if(seq.length-end>=n("minimum_tail"))seq.take(end)else seq)
    case "emboss_trimseq"=>dna(seq);val start=if(b("left"))seq.indexWhere(c=>c!='N'&&c!='n')else 0;val end=if(b("right"))seq.lastIndexWhere(c=>c!='N'&&c!='n')+1 else seq.length;output(h,if(start<0||end<=start)""else seq.substring(start,end))
    case "emboss_transeq"=>dna(seq);val forward=upper.replace('U','T');val reverse=forward.reverse.map(Genetics.complement(_));Vector(forward,reverse).zipWithIndex.foreach{case(s,strand)=>(0 until 3).foreach{frame=>active();output(s"${id}_${if(strand==0)(frame+1).toString else "-"+(frame+1)}",translate(s,frame))}}
    case "emboss_getorf"=>dna(seq);val forward=upper.replace('U','T');var number=0;(0 until 3).foreach{frame=>val aa=translate(forward,frame);var start=0;var i=0;while(i<=aa.length){if((i&4095)==0)active();if(i==aa.length||aa(i)=='*'){if(i-start>=n("minimum_amino_acids")){number+=1;require(number<=100000,"too many ORFs");output(s"${id}_orf_$number frame=${frame+1} start=${frame+start*3+1} end=${frame+i*3}",aa.substring(start,i))};start=i+1};i+=1}}
    case "emboss_fuzzpro"=>val pattern=t("pattern").toUpperCase(Locale.ROOT);require(seq.length.toLong*pattern.length<=64000000,"motif comparison budget exceeded");var hits=0;var i=0;while(i+pattern.length<=upper.length){if((i&4095)==0)active();val mismatches=pattern.indices.count(j=>pattern(j)!='X'&&pattern(j)!=upper(i+j));if(mismatches<=n("mismatches")){hits+=1;require(hits<=100000,"motif output budget exceeded");row(s"$id\t${i+1}\t${i+pattern.length}\t$mismatches\t${seq.substring(i,i+pattern.length)}")};i+=1}
    case "emboss_compseq"=>dna(seq);val counts=mutable.Map.empty[String,Long];var i=0;var total=0L;while(i+n("word")<=upper.length){if((i&4095)==0)active();val word=upper.substring(i,i+n("word"));if(word.forall("ACGT".contains(_))){counts(word)=counts.getOrElse(word,0L)+1;total+=1};i+=1};counts.toVector.sortBy(_._1).foreach{case(w,c)=>active();row(s"$id\t$w\t$c\t${fmt(c.toDouble/total)}")}
    case "emboss_freak"=>dna(seq);var i=0;var frequency=0;val width=n("window");if(upper.length>=width){frequency=upper.take(width).count(t("residues").contains(_));var previous=0;var windows=0;while(i.toLong+width<=upper.length){active();if(i>previous){if(i-previous<width){var k=previous;while(k<i){frequency-= (if(t("residues").contains(upper(k)))1 else 0);frequency+= (if(t("residues").contains(upper(k+width)))1 else 0);k+=1}}else frequency=upper.substring(i,i+width).count(t("residues").contains(_))};windows+=1;require(windows<=100000,"window output budget exceeded");row(s"$id\t${i+1}\t${i+width}\t${fmt(frequency.toDouble/width)}");previous=i;i+=n("step")}}
    case _=>throw new IllegalArgumentException("unknown sequence edit")
   }
  }
  def flush():Unit=title.foreach(h=>accept(h,sequence.toString))
  lines(input){s=>active();if(s.startsWith(">")){flush();require(s.drop(1).trim.nonEmpty&& !s.drop(1).contains('\t'),"valid FASTA header required");title=Some(s.drop(1));sequence.setLength(0)}else if(s.nonEmpty){require(title.nonEmpty&&s.forall(c=>c>='A'&&c<='Z'||c>='a'&&c<='z'||c=='*'||c=='-'||c=='.'),"invalid FASTA sequence");require(sequence.length.toLong+s.length<=8388608,"sequence exceeds 8 MiB characters");sequence.append(s)}};flush();if(operation=="emboss_nthseq")require(selected,"requested sequence ordinal absent")
 }
}
