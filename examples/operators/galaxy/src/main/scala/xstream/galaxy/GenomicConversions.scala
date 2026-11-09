package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

abstract class ExtendedGenomic(op:String) extends ExtendedOperator(op){
 override protected def format:String=operation match{case "split_paired_reads"=>"fastq";case "pileup_interval"=>"bed";case "vcf_to_pgsnp"=>"pgsnp";case _=>"tsv"}
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def n(k:String)=param(p,k).asInt;def row(s:String):Unit=emit(s+"\n")
  val fastq=scala.collection.mutable.ArrayBuffer.empty[String]
  var chromosome="";var start=0L;var end=0L;val bases=new java.lang.StringBuilder
  def flush():Unit=if(bases.length>0){row(s"$chromosome\t${start-1}\t$end\t$bases");bases.setLength(0)}
  lines(input){line=>
   active()
   operation match{
    case "split_paired_reads"=>fastq+=line;if(fastq.size==4){
     val h=fastq(0);val s=fastq(1);val plus=fastq(2);val quality=fastq(3)
     require(h.startsWith("@")&&h.length>1&&plus.startsWith("+")&&(plus.length==1||plus.drop(1)==h.drop(1)),"invalid FASTQ titles")
     require(s.length==quality.length&&s.length%2==0,"paired sequence/quality lengths must match and be even")
     require(s.forall(c=>c>32&&c<127)&&quality.forall(c=>c>=33&&c<=126),"invalid sequence or quality characters")
     val suffix="/"+n("mate");val half=s.length/2;val from=if(n("mate")==1)0 else half
     row(h+suffix);row(s.substring(from,from+half));row(if(plus.length==1)"+"else plus+suffix);row(quality.substring(from,from+half));fastq.clear()
    }
    case "pileup_interval"=>if(line.nonEmpty && !line.startsWith("#")){
     val f=line.split("\t",-1);def col(k:String):String={val i=n(k)-1;require(i<f.length,"selected pileup column missing");f(i)}
     val chr=col("sequence_column");val pos=col("position_column").toLong;val base=col("base_column");val coverage=col("coverage_column").toLong
     require(chr.nonEmpty&&pos>0&&coverage>=0&&base.length==1,"invalid pileup coordinates, base or coverage")
     if(coverage<n("min_coverage"))flush()else{
      if(bases.length>0&&(chromosome!=chr||end==Long.MaxValue||pos!=end+1))flush()
      if(bases.length==0){chromosome=chr;start=pos};end=pos;require(bases.length<8388608,"pileup active interval exceeds budget");bases.append(base)
     }
    }
    case "divide_pg_snp"=>if(line.nonEmpty && !line.startsWith("#")){
     val f=line.split("\t",-1);require(f.length>=7,"pgSnp requires seven fields");val count=f(4).toInt;require(count>0,"allele count must be positive")
     if(count<=2){val a=f(3).split("/",-1).toVector;val freq=f(5).split(",",-1).toVector;val score=f(6).split(",",-1).toVector
      require(a.size==count&&freq.size==count&&score.size==count&&a.forall(_.nonEmpty),"pgSnp allele arrays must match count");freq.foreach(x=>require(x.toLong>=0,"negative allele frequency"));score.foreach(x=>require(java.lang.Double.isFinite(x.toDouble),"invalid allele score"))
      val ref=n("reference_column");require(ref<=f.length,"reference column missing");val second=if(count==2)Vector(a(1),freq(1),score(1))else Vector(if(ref==0)"N"else f(ref-1),"0","0")
      row((f.take(3).toVector++Vector(a(0),freq(0),score(0))++second++f.drop(7)).mkString("\t"))
     }
    }
    case "vcf_to_pgsnp"=>if(line.nonEmpty && !line.startsWith("#")){
     val f=line.split("\t",-1);require(f.length>=10,"VCF requires sample columns");val pos=f(1).toLong;require(pos>0,"VCF position must be positive")
     val alleles=(Vector(f(3))++f(4).split(",",-1)).map(_.toUpperCase(Locale.ROOT))
     if(alleles.forall(a=>a.length==1&&"ACGT".contains(a.head))&&(f(6)=="PASS"||f(6)==".")){
      val gt=f(8).split(":",-1).indexOf("GT");require(gt>=0,"VCF FORMAT lacks GT")
      val selected=n("sample_column");require(selected<=f.length,"selected sample column missing");val samples=if(selected==0)f.drop(9).toVector else Vector(f(selected-1));val counts=Array.fill[Long](alleles.size)(0)
      samples.foreach{s=>active();val parts=s.split(":",-1);if(s!="."){require(gt<parts.length,"sample lacks GT value");parts(gt).split("[/|]",-1).foreach{a=>if(a!="."){require(a.matches("[0-9]+"),"invalid genotype allele");val k=a.toInt;require(k>=0&&k<counts.length,"genotype allele index outside ALT");counts(k)=Math.addExact(counts(k),1L)}}}}
      val called=counts.indices.filter(counts(_)>0);if(called.nonEmpty){val chr=if(param(p,"prefix_chr").asBoolean && !f(0).startsWith("chr"))"chr"+f(0)else f(0);row(Vector(chr,(pos-1).toString,pos.toString,called.map(alleles(_)).mkString("/"),called.size.toString,called.map(counts(_)).mkString(","),called.map(_=>"0").mkString(",")).mkString("\t"))}
     }
    }
    case "motif_flank_counts"=>if(line.nonEmpty){
     val f=line.split("\t",-1);require(f.length%2==1&&f.length>=3&&f(f.length/2)=="indel","expected odd window count and central indel")
     val center=f.length/2;val windows=n("windows");require(windows<=center,"not enough flanking windows")
     val motifs=param(p,"motifs").elements.asScala.map(_.asText).toVector;require(line.length.toLong*motifs.size<=64000000,"motif scan budget exceeded")
     motifs.foreach{s=>active();val parts=s.split("=",-1);val motif=parts.last.toUpperCase(Locale.ROOT);val name=parts.head
      def count(range:Range)=range.count(i=>f(i).toUpperCase(Locale.ROOT).contains(motif))
      row(s"$name\t${count(center-windows until center)}\t${count(center+1 to center+windows)}")
     }
    }
   }
  }
  if(operation=="split_paired_reads")require(fastq.isEmpty,"truncated four-line FASTQ record")
  if(operation=="pileup_interval")flush()
 }
}
