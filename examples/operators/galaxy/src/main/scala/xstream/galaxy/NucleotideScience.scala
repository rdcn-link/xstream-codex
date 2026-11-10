package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Bounded FASTA parsing shared by the nucleotide adapters. */
trait DnaFasta { self:ExtendedOperator =>
 protected def fasta(in:InputStream)(accept:(String,String)=>Unit):Unit={
  var title:Option[String]=None;val sequence=new java.lang.StringBuilder;var count=0
  def flush():Unit=title.foreach{h=>require(sequence.length>0,"nonempty DNA required");count+=1;require(count<=100000,"record budget exceeded");accept(h,sequence.toString)}
  lines(in){s=>active();if(s.startsWith(">")){flush();require(s.drop(1).trim.nonEmpty && !s.contains('\t'),"valid FASTA header required");title=Some(s.drop(1));sequence.setLength(0)}else if(s.nonEmpty){require(title.nonEmpty&&s.forall(c=>"ACGTacgt".contains(c)),"ACGT FASTA required");require(sequence.length.toLong+s.length<=1000000,"DNA record exceeds 1000000 bases");sequence.append(s.toUpperCase(Locale.ROOT))}};flush();require(count>0,"FASTA required")
 }
 protected def coding(s:String):Vector[String]={require(s.length%3==0,"complete coding sequence required");s.grouped(3).toVector}
}
abstract class NucleotideScience(op:String) extends ExtendedOperator(op) with DnaFasta {
 override protected def format:String="tsv"
 private def weights(p:JsonNode):Map[String,Double]={val a=param(p,"weights").elements.asScala.map{x=>val v=x.asText.split("=",-1);require(v.length==2&&v(0).length==3&&v(0).forall("ACGT".contains(_))&&Genetics.codons(v(0))!='*'&&v(1).matches("[0-9]{1,4}"),"sense codon=weight required");val w=v(1).toInt;require(w>=1&&w<=1000,"relative weight must be1..1000");v(0)->(w/1000d)}.toVector;require(a.nonEmpty&&a.map(_._1).distinct.size==a.size,"unique codon weights required");a.toMap}
 override protected def validate(p:JsonNode):Unit={super.validate(p)
  if(operation=="emboss_palindrome")require(param(p,"maximum_stem").asInt>=param(p,"minimum_stem").asInt,"maximum stem below minimum")
  if(Set("emboss_fuzznuc","emboss_fuzztran")(operation)){val pattern=param(p,"pattern").asText.toUpperCase(Locale.ROOT);require(pattern.nonEmpty&&pattern.length<=128&&pattern.forall(c=>if(operation=="emboss_fuzznuc")Genetics.iupac.contains(c)else"ACDEFGHIKLMNPQRSTVWYX".contains(c))&&param(p,"mismatches").asInt<=pattern.length,"invalid motif")}
  if(operation=="emboss_wobble"){val b=param(p,"bases").asText;require(b.nonEmpty&&b.distinct==b&&b.forall("ACGT".contains(_)),"unique ACGT bases required")}
  if(operation=="emboss_cai")weights(p)
 }
 protected def run(in:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n");def n(k:String)=param(p,k).asInt;def t(k:String)=param(p,k).asText;def fmt(v:Double)={require(v.isFinite,"nonfinite result");String.format(Locale.ROOT,"%.6f",Double.box(v))}
  val headings=Map("emboss_palindrome"->"sequence\tstart\tend\tstem\tloop","emboss_equicktandem"->"sequence\tstart\tend\tperiod\tcopies\tunit","emboss_fuzznuc"->"sequence\tstart\tend\tstrand\tmismatches\tmatch","emboss_fuzztran"->"sequence\tstart\tend\tframe\tmismatches\tpeptide","emboss_cpgreport"->"sequence\tlength\tgc_fraction\tcpg_count\tobserved_expected","emboss_cpgplot"->"sequence\tstart\tend","emboss_newcpgseek"->"sequence\tstart\tend\tscore","emboss_syco"->"sequence\tamino_acid\tcodons\tobserved\tentropy_bits","emboss_wobble"->"sequence\tstart_codon\tend_codon\twobble_fraction\tnonwobble_fraction","emboss_cai"->"sequence\tcodons\tcai");row(headings(operation))
  var comparisons=0L;var hits=0;def visit():Unit={comparisons+=1;if((comparisons&4095)==0)active();require(comparisons<=64000000,"comparison budget exceeded")};def hit(s:String):Unit={hits+=1;require(hits<=100000,"output hit budget exceeded");row(s)}
  fasta(in){(header,seq)=>val id=header.takeWhile(!_.isWhitespace);active()
   def reverse(s:String)=s.reverse.map(Genetics.complement(_))
   operation match{
    case "emboss_cpgreport"=>val c=seq.count(_=='C');val g=seq.count(_=='G');val cg=seq.sliding(2).count(_=="CG");row(s"$id\t${seq.length}\t${fmt((c+g).toDouble/seq.length)}\t$cg\t${fmt(if(c==0||g==0)0d else cg.toDouble*seq.length/(c.toDouble*g))}")
    case "emboss_cpgplot"=>val w=n("window");if(seq.length>=w){var c=seq.take(w).count(_=='C');var g=seq.take(w).count(_=='G');var cg=seq.take(w).sliding(2).count(_=="CG");var from= -1;var until=0
     def flush():Unit={if(from>=0&&until-from>=n("minimum_length"))hit(s"$id\t${from+1}\t$until");from= -1}
     (0 to seq.length-w).foreach{i=>active();if(i>0){if(seq(i-1)=='C')c-=1;if(seq(i-1)=='G')g-=1;if(seq(i+w-1)=='C')c+=1;if(seq(i+w-1)=='G')g+=1;if(seq.substring(i-1,i+1)=="CG")cg-=1;if(seq.substring(i+w-2,i+w)=="CG")cg+=1};val oe=if(c==0||g==0)0d else cg.toDouble*w/(c.toDouble*g);if((c+g)*100d/w>=n("minimum_gc_percent")&&oe*100>=n("minimum_oe_x100")){if(from>=0&&i>until)flush();if(from<0)from=i;until=i+w}};flush()}
    case "emboss_newcpgseek"=>var score=0L;var best=0L;var start=0;var end=0
     def flush():Unit={if(best>=n("threshold"))hit(s"$id\t${start+1}\t$end\t$best");score=0;best=0}
     (0 until seq.length-1).foreach{i=>if((i&4095)==0)active();if(score==0)start=i;score+= (if(seq.substring(i,i+2)=="CG")n("cpg_score")else -1);if(score>best){best=score;end=i+2};if(score<=0)flush()};flush()
    case "emboss_palindrome"=>(0 until seq.length).foreach{start=>active();(0 to n("maximum_loop")).foreach{loop=>var stem=n("minimum_stem");var best=0;while(stem<=n("maximum_stem")&&start+2*stem+loop<=seq.length){var j=0;var equal=true;while(j<stem&&equal){visit();equal=seq(start+j)==Genetics.complement(seq(start+2*stem+loop-1-j));j+=1};if(equal)best=stem;stem+=1};if(best>0)hit(s"$id\t${start+1}\t${start+2*best+loop}\t$best\t$loop")}}
    case "emboss_equicktandem"=>(1 to n("maximum_period")).foreach{period=>var i=0;while(i+period*n("minimum_copies").toLong<=seq.length){active();val unit=seq.substring(i,i+period);val primitive= !(1 until period).exists{k=>period%k==0&&(0 until period).forall{j=>visit();unit(j)==unit(j%k)}};if(primitive){var end=i+period;var same=true;while(same&&end+period<=seq.length){var j=0;while(j<period&&same){visit();same=seq(end+j)==unit(j);j+=1};if(same)end+=period};val copies=(end-i)/period;if(copies>=n("minimum_copies")){hit(s"$id\t${i+1}\t$end\t$period\t$copies\t$unit");i=end-period+1}else i+=1}else i+=1}}
    case "emboss_fuzznuc"|"emboss_fuzztran"=>val pattern=t("pattern").toUpperCase(Locale.ROOT);val translated=operation=="emboss_fuzztran";val strands=if(translated||param(p,"both_strands").asBoolean)Vector(seq,reverse(seq))else Vector(seq)
     strands.zipWithIndex.foreach{case(s,strand)=>val frames=if(translated)0 until 3 else 0 until 1;frames.foreach{frame=>val target=if(translated)s.drop(frame).grouped(3).filter(_.length==3).map(Genetics.codons).mkString else s;(0 to target.length-pattern.length).foreach{i=>var mismatch=0;pattern.indices.foreach{j=>visit();val ok=if(translated)pattern(j)=='X'||pattern(j)==target(i+j)else Genetics.iupac(pattern(j)).contains(target(i+j));if(!ok)mismatch+=1};if(mismatch<=n("mismatches")){val a=if(translated)frame+i*3 else i;val z=if(translated)frame+(i+pattern.length)*3 else i+pattern.length;val start=if(strand==0)a+1 else seq.length-z+1;val end=if(strand==0)z else seq.length-a;val marker=if(translated)(if(strand==0)frame+1 else -(frame+1)).toString else if(strand==0)"+"else"-";hit(s"$id\t$start\t$end\t$marker\t$mismatch\t${target.substring(i,i+pattern.length)}")}}}}
    case "emboss_syco"=>val counts=coding(seq).filter(Genetics.codons(_)!='*').groupMapReduce(identity)(_=>1)(_+_);counts.toVector.groupBy(x=>Genetics.codons(x._1)).toVector.sortBy(_._1).foreach{case(aa,values)=>val total=values.map(_._2).sum;val entropy= -values.map{x=>val q=x._2.toDouble/total;q*math.log(q)/math.log(2)}.sum;hit(s"$id\t$aa\t${values.size}\t$total\t${fmt(if(entropy==0)0d else entropy)}")}
    case "emboss_wobble"=>val codons=coding(seq);val width=n("window");var third=0;var other=0;def change(c:String,sign:Int):Unit={third+= (if(t("bases").contains(c(2)))sign else 0);other+=c.take(2).count(t("bases").contains(_))*sign};if(codons.size>=width){codons.take(width).foreach(change(_,1));(0 to codons.size-width).foreach{i=>active();if(i>0){change(codons(i-1),-1);change(codons(i+width-1),1)};hit(s"$id\t${i+1}\t${i+width}\t${fmt(third.toDouble/width)}\t${fmt(other.toDouble/(width*2))}")}}
    case "emboss_cai"=>val w=weights(p);val codons=coding(seq).filter(Genetics.codons(_)!='*');require(codons.nonEmpty&&codons.forall(w.contains),"weights required for every observed sense codon");row(s"$id\t${codons.size}\t${fmt(math.exp(codons.map(c=>math.log(w(c))).sum/codons.size))}")
   }
  }
 }
}
abstract class CodonComparison(op:String) extends PairedStreams(op) with DnaFasta {
 override protected def format:String="tsv"
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def read(i:InputStream):(String,Vector[String])={var all=Vector.empty[(String,Vector[String])];fasta(i){(h,s)=>all:+=h.takeWhile(!_.isWhitespace)->coding(s)};require(all.size==1,"exactly one coding sequence required");all.head}
  val(a,x)=read(source);val(b,y)=read(reference);val xc=x.groupMapReduce(identity)(_=>1)(_+_);val yc=y.groupMapReduce(identity)(_=>1)(_+_);val divergence=(xc.keySet++yc.keySet).toVector.sorted.map{k=>active();val u=xc.getOrElse(k,0).toDouble/x.size;val v=yc.getOrElse(k,0).toDouble/y.size;val m=(u+v)/2;(if(u==0)0d else u*math.log(u/m)/math.log(2)/2)+(if(v==0)0d else v*math.log(v/m)/math.log(2)/2)}.sum
  emit("query\treference\tquery_codons\treference_codons\tjs_divergence_bits\n");emit(s"$a\t$b\t${x.size}\t${y.size}\t${String.format(Locale.ROOT,"%.6f",Double.box(if(divergence==0)0d else divergence))}\n")
 }
}
