package xstream.galaxy

import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

object Genetics {
 val iupac:Map[Char,String]=Map('A'->"A",'C'->"C",'G'->"G",'T'->"T",'U'->"T",'R'->"AG",'Y'->"CT",'S'->"CG",'W'->"AT",'K'->"GT",'M'->"AC",'B'->"CGT",'D'->"AGT",'H'->"ACT",'V'->"ACG",'N'->"ACGT")
 val complements="ACGTRYMKBDHVSWNU-".zip("TGCAYRKMVHDBSWNA-").toMap
 def complement(c:Char,rna:Boolean=false):Char={val x=complements.getOrElse(c.toUpper,throw new IllegalArgumentException("invalid nucleotide"));val y=if(rna&&x=='T')'U'else x;if(c.isLower)y.toLower else y}
 val codons:Map[String,Char]={val nts="TCAG";val aa="FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG";(for(a<-nts;b<-nts;c<-nts)yield s"$a$b$c").zip(aa).toMap}
}
abstract class ExtendedSequence(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(Set("reverse_complement","translate","colorspace_to_fasta")(operation))"fasta"else if(operation=="find_subsequences")"bed"else"tsv"
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n");def t(k:String)=param(p,k).asText;def b(k:String)=param(p,k).asBoolean
  var header:Option[String]=None;val record=new java.lang.StringBuilder
  val counts=scala.collection.mutable.Map.from(Genetics.codons.keys.toVector.map(_->0L))
  val lengths=scala.collection.mutable.ArrayBuffer.empty[Long];val contigs=scala.collection.mutable.ArrayBuffer.empty[Long];val global=scala.collection.mutable.Map.from("ACGTN".map(_->0L));var gapCount=0L
  if(operation=="base_distribution")row("Gene\tA\tC\tG\tT\tLength\tCG%")
  def accept(title:String,sequence:String):Unit={
   active();val seq=sequence.toUpperCase(Locale.ROOT);val id=title.takeWhile(!_.isWhitespace)
   operation match{
    case "reverse_complement"=>val rna=t("alphabet")=="rna";require(seq.indexOf(if(rna)'T'else'U')<0,"sequence alphabet does not match configured DNA/RNA");row(">"+title);val out=sequence.reverseIterator.map(Genetics.complement(_,rna)).mkString;row(out)
    case "colorspace_to_fasta"=>require(seq.nonEmpty&&"ACGT".contains(seq.head),"SOLiD leading nucleotide must be A/C/G/T");var state="ACGT".indexOf(seq.head);val out=new java.lang.StringBuilder;if(b("keep_prefix"))out.append(seq.head)
     seq.drop(1).zipWithIndex.foreach{case(c,i)=>if((i&4095)==0)active();require(c>='0'&&c<='3',"invalid SOLiD color");state=state^(c-'0');out.append("ACGT"(state))};row(">"+title);row(out.toString)
    case "translate"=>require(seq.forall(Genetics.iupac.contains),"invalid nucleotide");val dna=seq.replace('U','T');require(dna.length%3==0,"partial terminal codon")
     val aa=dna.grouped(3).map(c=>Genetics.codons.getOrElse(c,'X')).mkString
     val translated=if(b("cds")){require(dna.startsWith("ATG")&&aa.nonEmpty&&aa.last=='*' && !aa.dropRight(1).contains('*'),"invalid complete CDS");aa.dropRight(1)}else if(b("to_stop"))aa.takeWhile(_!='*')else aa
     row(">"+title);row(translated)
    case "codon_count"=>require(seq.length%3==0&&seq.forall("ACGT".contains(_)),"codon_count requires complete unambiguous DNA codons");seq.grouped(3).zipWithIndex.foreach{case(c,i)=>if((i&4095)==0)active();counts(c)=Math.addExact(counts(c),1L)}
    case "base_distribution"=>require(seq.forall(Genetics.iupac.contains),"invalid nucleotide");val numbers="ACGT".map(c=>seq.count(_==c));val gc=if(seq.isEmpty)0d else (numbers(1)+numbers(2)).toDouble/seq.length;row((Vector(id)++numbers.map(_.toString)++Vector(seq.length.toString,String.format(Locale.ROOT,"%.6f",Double.box(gc)))).mkString("\t"))
    case "find_subsequences"=>require(seq.forall(Genetics.iupac.contains),"invalid nucleotide");val pattern=t("pattern").toUpperCase(Locale.ROOT).replace('U','T');require(seq.length.toLong*pattern.length<=64000000,"motif comparison budget exceeded")
     val strands=if(t("strand")=="both")Vector("+","-")else Vector(if(t("strand")=="forward")"+"else"-")
     strands.foreach{strand=>val query=if(strand=="+")pattern else pattern.reverse.map(Genetics.complement(_));val code=query.map(Genetics.iupac(_).toSet)
      var i=0;var hits=0;while(i+query.length<=seq.length){if((i&4095)==0)active();if(query.indices.forall(j=>code(j)(seq(i+j)))){hits+=1;require(hits<=100000,"motif hits exceed output budget");row(s"$id\t$i\t${i+query.length}\t$title\t\t$strand")};i+=1}
     }
    case "fasta_stats"=>require(seq.forall(Genetics.iupac.contains),"invalid nucleotide");require(lengths.size<100000,"too many sequences");lengths+=seq.length.toLong;"ACGTN".foreach(c=>global(c)=Math.addExact(global(c),seq.count(_==c).toLong))
     var start=0;var i=0;while(i<seq.length){if((i&4095)==0)active();if(seq(i)=='N'){if(i>start){require(contigs.size<100000,"too many contigs");contigs+=(i-start).toLong};val at=i;while(i<seq.length&&seq(i)=='N')i+=1;if(at>0&&i<seq.length)gapCount+=1;start=i}else i+=1};if(start<seq.length){require(contigs.size<100000,"too many contigs");contigs+=(seq.length-start).toLong}
   }
  }
  def flush():Unit=header.foreach(h=>accept(h,record.toString))
  lines(input){s=>if(s.nonEmpty && !s.startsWith("#")){
   if(s.startsWith(">")){flush();require(s.length>1,"FASTA title missing");header=Some(s.drop(1));record.setLength(0)}
   else{require(header.nonEmpty,"sequence must follow FASTA header");val value=s.filterNot(_.isWhitespace);require(record.length.toLong+value.length<=8388608,"sequence exceeds 8 MiB characters");record.append(value)}
  }};flush()
  if(operation=="codon_count")counts.keys.toVector.sorted.foreach(k=>row(k+"\t"+counts(k)))
  if(operation=="fasta_stats"){
   def stats(label:String,values:Seq[Long]):Unit={
    val sorted=values.sorted.reverse;val sum=sorted.sum;val mean=if(sorted.isEmpty)0d else sum.toDouble/sorted.size
    def nx(percent:Double):(Long,Int)={if(sum==0)(0L,0)else{var total=0L;var i=0;while(i<sorted.size&&total<sum*percent){total+=sorted(i);i+=1};(sorted(i-1),i)}}
    val a=nx(.5);val c=nx(.9);row(s"$label L50\t${a._2}");row(s"$label N50\t${a._1}");row(s"$label L90\t${c._2}");row(s"$label N90\t${c._1}")
    val genome=param(p,"genome_size").asInt.toLong;if(genome>0){var total=0L;val ng=sorted.find{x=>total+=x;total>=genome/2d}.getOrElse(0L);row(s"$label NG50\t$ng")}
    row(s"$label len_max\t${sorted.headOption.getOrElse(0L)}");row(s"$label len_min\t${sorted.lastOption.getOrElse(0L)}");row(s"$label len_mean\t${mean.toLong}")
    val mid=sorted.size/2;val median=if(sorted.isEmpty)0L else if(sorted.size%2==1)sorted(mid)else (sorted(mid-1)+sorted(mid))/2
    row(s"$label len_median\t$median");val variance=if(sorted.isEmpty)0d else sorted.map(x=>math.pow(x-mean,2)).sum/sorted.size;row(s"$label len_std\t${math.sqrt(variance).toLong}")
    if(label=="Scaffold"){"ATCGN".foreach(c=>row(s"$label num_$c\t${global(c)}"))}
    row(s"$label num_bp\t$sum");if(label=="Scaffold")row(s"$label num_bp_not_N\t${sum-global('N')}");row(s"$label num_seq\t${values.size}")
    if(label=="Scaffold"){val gc=if(sum==0)0d else (global('G')+global('C'))*100d/sum;row(s"$label GC content overall\t"+String.format(Locale.ROOT,"%.2f",Double.box(gc)))}
   };stats("Scaffold",lengths.toVector);stats("Contig",contigs.toVector);row("Number of gaps\t"+gapCount)
  }
 }
}
