package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable

abstract class BedBlocks(op:String) extends ExtendedOperator(op){
 override protected def format:String="bed"
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit=lines(input){s=>if(s.nonEmpty && !s.startsWith("#")){active();val f=s.split("\t",-1).toVector;require(f.size==12,"BED12 required");val start=f(1).toLong;val end=f(2).toLong;require(f(0).nonEmpty&&start>=0&&end>start&&end<=2147483647L&&Set("+","-",".")(f(5)),"invalid BED12 interval");val count=f(9).toInt;require(count>0&&count<=100000,"invalid block count");val sizes=f(10).stripSuffix(",").split(",",-1).map(_.toLong);val offsets=f(11).stripSuffix(",").split(",",-1).map(_.toLong);require(sizes.length==count&&offsets.length==count,"block count mismatch");var last=0L;sizes.indices.foreach{i=>active();require(sizes(i)>0&&offsets(i)>=last&&offsets(i)+sizes(i)<=end-start,"invalid BED12 blocks");val a=start+offsets(i);val z=a+sizes(i);last=offsets(i)+sizes(i);emit(Vector(f(0),a.toString,z.toString,f(3),f(4),f(5)).mkString("\t")+"\n")}}}
}
abstract class GenomeStreams(op:String) extends PairedStreams(op){
 override protected def format:String=if(Set("bed_extract_fasta","bed_mask_fasta")(operation))"fasta"else"tsv"
 case class Region(f:Vector[String],a:Long,z:Long){def chr=f.head}
 private def beds(in:InputStream):Vector[Region]={val out=mutable.ArrayBuffer.empty[Region];var total=0L;lines(in){s=>if(s.nonEmpty && !s.startsWith("#")){active();total+=s.length+64;require(total<=8388608&&out.size<100000,"interval buffer exceeded");val f=s.split("\t",-1).toVector;require(f.size>=3&&f.head.nonEmpty && !f.head.exists(_.isWhitespace),"BED3 required");val a=f(1).toLong;val z=f(2).toLong;require(a>=0&&z>a&&z<=2147483647L,"invalid BED interval");out+=Region(f,a,z)}};out.toVector}
 private def fasta(in:InputStream):Vector[(String,String)]={val out=mutable.ArrayBuffer.empty[(String,String)];var title:Option[String]=None;val seq=new java.lang.StringBuilder;var total=0L
  def flush():Unit=title.foreach{h=>require(out.size<100000,"too many sequences");out+=h->seq.toString}
  lines(in){s=>active();total+=s.length+1;require(total<=8388608,"FASTA buffer exceeded");if(s.startsWith(">")){flush();require(s.drop(1).trim.nonEmpty,"missing FASTA title");title=Some(s.drop(1));seq.setLength(0)}else if(s.nonEmpty){require(title.nonEmpty&&s.forall(c=>Genetics.iupac.contains(c.toUpper)),"invalid nucleotide");seq.append(s)}};flush();require(out.map(_._1.takeWhile(!_.isWhitespace)).distinct.size==out.size,"duplicate FASTA IDs");out.toVector
 }
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n")
  def fmt(v:Double)=String.format(Locale.ROOT,"%.6f",Double.box(v))
  if(Set("bed_extract_fasta","bed_mask_fasta","bed_nucleotide_content")(operation)){
   val (regions,sequences)=if(operation=="bed_mask_fasta"){val r=beds(reference);(r,fasta(source))}else{val f=fasta(reference);(beds(source),f)}
   val dna=sequences.map{case(h,s)=>h.takeWhile(!_.isWhitespace)->s}.toMap
   regions.foreach{r=>require(dna.contains(r.chr)&&r.z<=dna(r.chr).length,"interval outside FASTA reference")}
   if(operation=="bed_mask_fasta"){
    val grouped=regions.groupBy(_.chr);sequences.foreach{case(h,seq)=>active();val id=h.takeWhile(!_.isWhitespace);val bases=seq.toCharArray;val events=grouped.getOrElse(id,Vector.empty).flatMap(r=>Vector(r.a->1,r.z-> -1)).groupMapReduce(_._1)(_._2)(_+_).toVector.sortBy(_._1);var depth=0;var start=0L
     events.foreach{case(at,delta)=>active();if(depth>0){var i=start;while(i<at){if((i&4095)==0)active();bases(i.toInt)=if(param(p,"soft").asBoolean)bases(i.toInt).toLower else'N';i+=1}};depth+=delta;start=at};row(">"+h);row(new String(bases))
    }
   }else regions.foreach{r=>active();val raw=dna(r.chr).substring(r.a.toInt,r.z.toInt)
    if(operation=="bed_extract_fasta"){val stranded=param(p,"stranded").asBoolean;require(!stranded||r.f.size>=6&&Set("+","-")(r.f(5)),"BED6 strand required");val strand=if(stranded)r.f(5)else"";val seq=if(strand=="-")raw.reverse.map(Genetics.complement(_))else raw;row(s">${r.chr}:${r.a}-${r.z}"+(if(stranded)s"($strand)"else""));row(seq)}
    else{val seq=raw.toUpperCase(Locale.ROOT);val counts="ACGTN".map(c=>seq.count(_==c));val other=seq.length-counts.sum;val at=(counts(0)+counts(3)).toDouble/seq.length;val gc=(counts(1)+counts(2)).toDouble/seq.length;row((r.f++Vector(fmt(at),fmt(gc))++counts.map(_.toString)++Vector(other.toString,seq.length.toString)).mkString("\t"))}
   }
  }else{
   val right=beds(reference);val left=beds(source);val tracks=Vector(left,right);val grouped=tracks.map(_.groupBy(_.chr));val chromosomes=(left.map(_.chr)++right.map(_.chr)).distinct.sorted
   if(operation=="bed_union_bedgraph")tracks.foreach{v=>v.groupBy(_.chr).values.foreach{all=>var end=0L;all.sortBy(_.a).foreach{r=>active();require(r.f.size==4&&r.a>=end,"nonoverlapping BED4 required");end=r.z}}}
   chromosomes.foreach{chr=>active();val values=grouped.map(_.getOrElse(chr,Vector.empty).sortBy(_.a));val events=mutable.Map.empty[Long,mutable.ArrayBuffer[(Int,Int,Region)]]
    values.zipWithIndex.foreach{case(rows,i)=>rows.foreach{r=>events.getOrElseUpdate(r.a,mutable.ArrayBuffer.empty)+=((i,1,r));events.getOrElseUpdate(r.z,mutable.ArrayBuffer.empty)+=((i,-1,r))}}
    val activeCounts=Array(0,0);val current=Array("","");var previous=0L;var pending:Option[(Long,Long,Vector[String])]=None
    def flush():Unit=pending.foreach{case(a,z,v)=>row((Vector(chr,a.toString,z.toString)++v).mkString("\t"))}
    events.toVector.sortBy(_._1).foreach{case(at,changes)=>active();if(at>previous&&activeCounts.exists(_>0)){
     val fields=if(operation=="bed_multi_intersect"){val flags=activeCounts.map(n=>if(n>0)1 else 0);Vector(flags.sum.toString,flags.indices.filter(flags(_)>0).map(_+1).mkString(","))++flags.map(_.toString)}else activeCounts.indices.map(i=>if(activeCounts(i)>0)current(i)else param(p,"filler").asText).toVector
     if(operation=="bed_multi_intersect"&&pending.exists(x=>x._2==previous&&x._3==fields))pending=pending.map(x=>(x._1,at,fields))else{flush();pending=Some((previous,at,fields))}
    }else if(at>previous){flush();pending=None}
     changes.sortBy(_._2).foreach{case(i,d,r)=>activeCounts(i)+=d;if(d>0)current(i)=r.f.lift(3).getOrElse("")};previous=at
    };flush()
   }
  }
 }
}
