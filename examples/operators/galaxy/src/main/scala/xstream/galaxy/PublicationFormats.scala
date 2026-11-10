package xstream.galaxy

import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable

/** Reviewed text conversions. Coordinates and hierarchy restrictions belong to these adapters. */
abstract class PublicationFormats(op:String) extends ExtendedOperator(op) {
 override protected def format:String=if(operation=="remove_terminal_stop_codons")"fasta" else if(operation=="resize_coordinate_window")"gff" else if(operation=="windowmasker_to_bed")"bed" else "tsv"
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def t(k:String)=param(p,k).asText;def n(k:String)=param(p,k).asInt.toLong;def b(k:String)=param(p,k).asBoolean
  def row(s:String):Unit=emit(s+"\n")
  var index=0L;var count=0L;var chrom="";var header:Option[String]=None
  val sequence=new java.lang.StringBuilder;val ids=mutable.HashSet.empty[String]
  val records=mutable.ArrayBuffer.empty[Array[String]];val chroms=mutable.LinkedHashSet.empty[String];var chars=0L
  def token(s:String):String={require(s.nonEmpty && !s.exists(c=>c.isWhitespace||c==','||c=='='),"invalid unescaped Circos token");s}
  def range(f:Array[String],start:Int,end:Int,min:Long):Unit={val a=f(start).toLong;val z=f(end).toLong;require(a>=min&&z>=a&&z<=2147483647L,"invalid interval coordinates")}
  def gff(s:String):Array[String]={val f=s.split("\t",-1);require(f.length==9&&f(0).nonEmpty&&f(2).nonEmpty,"nine-column GFF required");range(f,3,4,1);require(Set("+","-",".","?")(f(6)),"invalid GFF strand");f}
  def fasta():Unit=header.foreach{title=>
   active();val id=title.takeWhile(!_.isWhitespace);val seq=sequence.toString
   if(operation=="circos_karyotype_fasta"){require(ids.size<100000&&ids.add(id),"duplicate or excessive FASTA IDs");token(id);row(s"chr\t-\t$id\t$id\t0\t${seq.length}\tchr${index}color");index+=1}
   else {val dna=seq.toUpperCase(Locale.ROOT).replace('U','T');require(dna.forall(Genetics.iupac.contains),"invalid nucleotide");var end=dna.length;val stops=Set("TAA","TAG","TGA");while(end>=3&&stops(dna.substring(end-3,end))){active();end-=3};val clean=dna.substring(0,end)
    if(b("check_internal"))clean.grouped(3).foreach(c=>require(!stops(c),"internal stop codon"));row(">"+title);row(clean)
   }
  }
  lines(input){s=>
   active()
   if(Set("circos_karyotype_fasta","remove_terminal_stop_codons")(operation)){
    if(s.startsWith(">")){fasta();require(s.drop(1).trim.nonEmpty,"missing FASTA title");header=Some(s.drop(1));sequence.setLength(0)}
    else if(s.nonEmpty){require(header.nonEmpty,"sequence before FASTA title");val value=s.filterNot(_.isWhitespace);require(sequence.length.toLong+value.length<=8388608,"sequence exceeds 8 MiB characters");sequence.append(value)}
   }else if(operation=="windowmasker_to_bed"){
    if(s.startsWith(">")){chrom=s.drop(1).takeWhile(!_.isWhitespace);require(chrom.nonEmpty,"missing chromosome")}
    else if(s.trim.nonEmpty){require(chrom.nonEmpty,"interval before chromosome");val m="([0-9]+)\\s*-\\s*([0-9]+)".r;val(a,z)=s.trim match{case m(x,y)=>(x.toLong,y.toLong);case _=>throw new IllegalArgumentException("invalid WindowMasker interval")};require(a>=0&&z>=a&&z<2147483647L,"invalid coordinates");row(s"$chrom\t$a\t${z+1}\twm_$index");index+=1}
   }else if(s.nonEmpty && !s.startsWith("#"))operation match {
    case "circos_karyotype_lengths"=>val f=s.split("\t",-1);require(f.length>=2,"length table needs two columns");val id=token(f(0));val len=f(1).toLong;require(len>0&&len<=2147483647L&&ids.size<100000&&ids.add(id),"invalid length or duplicate ID");row(s"chr\t-\t$id\t$id\t0\t$len\tchr${index}color");index+=1
    case "circos_tiles_bed"|"circos_text_bed"=>val f=s.trim.split("\\s+");require(f.length>=3,"BED3 required");range(f,1,2,0);token(f(0));val full=f.length>=6
     if(operation=="circos_text_bed")require(full,"BED6 required")
     val strand=if(full){require(Set("+","-",".")(f(5)),"invalid BED strand");if(f(5)=="+")"1"else if(f(5)=="-")"-1"else"."}else""
     if(full){token(f(3));token(f(4))}
     if(operation=="circos_tiles_bed")row(f.take(3).mkString("\t")+"\t"+(if(full)s"name=${f(3)},strand=$strand,value=${f(4)}"else""))
     else row(f.take(4).mkString("\t")+s"\tstrand=$strand,value=${f(4)}")
    case "circos_tiles_gff3"|"circos_text_gff3"=>val f=gff(s);val attr=f(8).split(";",-1).filter(_.nonEmpty).map{x=>val at=x.indexOf('=');require(at>0,"GFF3 attributes required");x.take(at)->x.drop(at+1)}.toMap;require(!attr.contains("Parent"),"hierarchical GFF3 is not a flat Circos input");chars+=s.length+64;require(chars<=8388608&&records.size<100000,"GFF sorting buffer exceeded");records+=f;chroms+=f(0)
    case "count_gff_features"=>gff(s);count+=1
    case "resize_coordinate_window"=>val f=gff(s);val start=f(3).toLong;val end=f(4).toLong;val mid=(start+end)/2;var a=(if(b("existing"))start else mid)-n("left");var z=(if(b("existing"))end else mid)+n("right");val outside=a<n("start_coordinate")||z>n("chromosome_length")
     if(!outside||t("boundaries")!="discard"){
      require(!outside||t("boundaries")!="error","resized interval outside chromosome");a=math.max(a,n("start_coordinate"));z=math.min(z,n("chromosome_length"));require(a<=z,"resized interval is inverted");f(3)=a.toString;f(4)=z.toString;row(f.mkString("\t"))
     }
    case _=>throw new IllegalArgumentException("unsupported conversion")
   }
  }
  if(Set("circos_karyotype_fasta","remove_terminal_stop_codons")(operation))fasta()
  if(operation=="count_gff_features")row(count.toString)
  if(Set("circos_tiles_gff3","circos_text_gff3")(operation)){
   require(t("attribute").nonEmpty,"attribute must not be empty")
   val order=chroms.toVector.zipWithIndex.toMap
   records.sortBy(f=>(order(f(0)),f(3).toLong)).foreach{f=>active();val attrs=f(8).split(";").filter(_.contains("=")).map{x=>val at=x.indexOf('=');x.take(at)->x.drop(at+1)}.toMap
    val name=attrs.get(t("attribute")).map(_.takeWhile(_!=','));val strand=if(f(6)=="+")"1"else if(f(6)=="-")"-1"else"0";val value=if(f(5)==".")"0"else f(5);val prefix=s"${token(f(0))}\t${f(3).toLong-1}\t${f(4)}"
    if(operation=="circos_tiles_gff3")row(prefix+s"\tname=${token(name.getOrElse("None"))},strand=$strand,type=${token(f(2))},value=${token(value)}")
    else name.foreach(x=>row(prefix+s"\t${token(x)}\tstrand=$strand,value=${token(value)}"))
   }
  }
 }
}
