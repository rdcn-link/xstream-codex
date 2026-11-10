package xstream.galaxy
import java.io.InputStream
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable

abstract class SequencePairs(op:String) extends PairedStreams(op){
 override protected def format:String=if(operation=="seqtk_mergepe")"fastq"else"fasta"
 private case class Sequence(title:String,data:String,quality:Option[String]=None){def id=title.takeWhile(!_.isWhitespace)}
 private def fasta(in:InputStream):Vector[Sequence]={
  val all=mutable.ArrayBuffer.empty[Sequence];var title:Option[String]=None;val bases=new java.lang.StringBuilder;var total=0L
  def flush():Unit=title.foreach{h=>require(all.size<100000,"too many sequences");all+=Sequence(h,bases.toString)}
  lines(in){s=>active();total+=s.length+1;require(total<=8388608,"FASTA buffer exceeded");if(s.startsWith(">")){flush();require(s.drop(1).trim.nonEmpty,"missing FASTA title");title=Some(s.drop(1));bases.setLength(0)}else if(s.nonEmpty){require(title.nonEmpty&&s.forall(c=>Genetics.iupac.contains(c.toUpper)),"invalid FASTA sequence");bases.append(s)}};flush();require(all.map(_.id).distinct.size==all.size,"duplicate FASTA IDs");all.toVector
 }
 private def fastq(in:InputStream):Vector[Sequence]={
  val all=mutable.ArrayBuffer.empty[Sequence];val fields=mutable.ArrayBuffer.empty[String];var total=0L
  lines(in){s=>active();total+=s.length+1;require(total<=8388608,"FASTQ buffer exceeded");fields+=s;if(fields.size==4){require(all.size<100000&&fields(0).startsWith("@")&&fields(0).drop(1).trim.nonEmpty&&fields(2).startsWith("+"),"invalid FASTQ structure");require(fields(1).nonEmpty&&fields(1).forall(c=>Genetics.iupac.contains(c.toUpper))&&fields(1).length==fields(3).length&&fields(3).forall(c=>c>=33&&c<=126),"invalid FASTQ sequence/quality");require(fields(2)=="+"||fields(2).drop(1)==fields(0).drop(1),"FASTQ plus header mismatch");all+=Sequence(fields(0).drop(1),fields(1),Some(fields(3)));fields.clear()}}
  require(fields.isEmpty,"incomplete FASTQ record");all.toVector
 }
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n")
  if(operation=="seqtk_mutfa"){
   val mutations=mutable.LinkedHashMap.empty[(String,Int),Char];var total=0L
   lines(reference){s=>active();if(s.nonEmpty && !s.startsWith("#")){total+=s.length+64;require(total<=8388608&&mutations.size<100000,"mutation buffer exceeded");val f=s.split("\t",-1);require(f.length==4&&f(0).nonEmpty&&f(1).matches("[0-9]{1,10}")&&f(1).toLong>=1&&f(1).toLong<=2147483647L&&f(3).length==1&&Genetics.iupac.contains(f(3).head.toUpper),"invalid mutation row");val key=f(0)->(f(1).toInt-1);require(!mutations.contains(key),"duplicate mutation");mutations(key)=f(3).head}}
   val sequences=fasta(source);val grouped=mutations.toVector.groupBy(_._1._1);require(grouped.keySet.subsetOf(sequences.map(_.id).toSet),"unknown mutation sequence")
   sequences.foreach{s=>active();val bases=s.data.toCharArray;grouped.getOrElse(s.id,Vector.empty).foreach{case((_,pos),base)=>require(pos<bases.length,"mutation outside sequence");bases(pos)=base};row(">"+s.title);row(new String(bases))}
  }else{
   val right=if(operation=="seqtk_mergepe")fastq(reference)else fasta(reference);val left=if(operation=="seqtk_mergepe")fastq(source)else fasta(source);require(left.size==right.size,"paired sequence counts differ")
   val codes=Genetics.iupac.filter(_._1!='U').map{case(c,bases)=>bases.toSet->c}
   left.zip(right).foreach{case(a,b)=>active()
    if(operation=="seqtk_mergepe"){
     def base(s:String)=s.stripSuffix("/1").stripSuffix("/2")
     require(base(a.id)==base(b.id),"mate names differ");Vector(a,b).foreach{s=>row("@"+s.title);row(s.data);row("+");row(s.quality.get)}
    }else{
     require(a.id==b.id&&a.data.length==b.data.length,"FASTA IDs or lengths differ");val merged=new java.lang.StringBuilder
     a.data.zip(b.data).zipWithIndex.foreach{case((x,y),i)=>if((i&4095)==0)active();val bases=Genetics.iupac(x.toUpper).toSet++Genetics.iupac(y.toUpper).toSet;val code=codes(bases);merged.append(if(x.isUpper&&y.isUpper)code else code.toLower)};row(">"+a.id);row(merged.toString)
    }
   }
  }
 }
}
