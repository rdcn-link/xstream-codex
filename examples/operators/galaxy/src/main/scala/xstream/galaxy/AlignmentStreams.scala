package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

abstract class AlignmentStreams(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(operation=="sam_to_fastq")"fastq"else if(Set("sam_sort","sam_collate","sam_reheader","sam_calmd")(operation))"sam"else"tsv"
 private def dictionary(header:Vector[String]):Vector[(String,Long)]={
  val d=header.filter(_.startsWith("@SQ\t")).map{s=>val f=s.split("\t").drop(1);val sn=f.filter(_.startsWith("SN:"));val ln=f.filter(_.startsWith("LN:"));require(sn.length==1&&ln.length==1&&sn(0).length>3,"invalid @SQ");val l=ln(0).drop(3).toLong;require(l>0&&l<=2147483647L,"invalid reference length");sn(0).drop(3)->l}
  require(d.map(_._1).distinct.size==d.size,"duplicate reference");d
 }
 override protected def validate(p:JsonNode):Unit={super.validate(p)
  if(operation=="sam_reheader"){val h=param(p,"header_lines").elements.asScala.map(_.asText).toVector;require(h.nonEmpty&&h.forall(_.matches("@(HD|SQ|RG|PG|CO)\\t.*")),"invalid replacement header");dictionary(h)}
  if(operation=="sam_calmd")references(p)
 }
 private def references(p:JsonNode):Map[String,String]={val all=param(p,"reference").asText.split(",",-1).toVector.map{x=>val at=x.indexOf('=');require(at>0&&at<x.length-1,"reference must be name=DNA");val dna=x.drop(at+1).toUpperCase(Locale.ROOT);require(dna.forall("ACGTN".contains(_)),"reference must be ACGTN");x.take(at)->dna};require(all.map(_._1).distinct.size==all.size,"duplicate reference");all.toMap}
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n")
  def fmt(x:Double)=String.format(Locale.ROOT,"%.6f",Double.box(x))
  val header=mutable.ArrayBuffer.empty[String];var started=false;var dict=Vector.empty[(String,Long)];var lengths=Map.empty[String,Long];var ranks=Map.empty[String,Int];var totalChars=0L
  val records=mutable.ArrayBuffer.empty[Vector[String]];val blocks=mutable.Map.empty[String,mutable.ArrayBuffer[(Long,Long)]];val referenceCounts=mutable.Map.empty[String,Array[Long]];var unknownUnmapped=0L
  val flagNames=Vector("total","primary","secondary","supplementary","duplicates","mapped","paired","read1","read2","proper_pair","singletons");val flags=Array.fill(flagNames.size,2)(0L)
  var recordCount=0L;var mappedCount=0L;var baseCount=0L;var alignedCount=0L;var mapqTotal=0L;var blockCount=0L
  val refs=if(operation=="sam_calmd")references(p)else Map.empty[String,String]
  def begin():Unit=if(!started){started=true;dict=dictionary(header.toVector);require(dict.size<=100000,"too many references");lengths=dict.toMap;ranks=dict.map(_._1).zipWithIndex.toMap;dict.foreach{case(n,_)=>referenceCounts(n)=Array(0L,0L)}
   if(format=="sam"&& !Set("sam_sort","sam_reheader")(operation))header.foreach(row)
   if(operation=="sam_reheader"){val h=param(p,"header_lines").elements.asScala.map(_.asText).toVector;require(dictionary(h)==dict,"replacement dictionary differs");h.foreach(row)}
  }
  lines(input){s=>active()
   if(s.startsWith("@")){
    require(!started&&s.matches("@(HD|SQ|RG|PG|CO)\\t.*"),"invalid or misplaced SAM header");totalChars+=s.length+1;require(totalChars<=8388608,"header budget exceeded");header+=s
   }else{
    begin();val f=s.split("\t",-1).toVector;require(f.size>=11,"SAM requires eleven fields");require(f(0).nonEmpty && !f(0).exists(_.isWhitespace),"invalid read name")
    val flag=f(1).toInt;val pos=f(3).toLong;val mapq=f(4).toInt;require(flag>=0&&flag<=65535&&pos>=0&&pos<=2147483647L&&mapq>=0&&mapq<=255,"invalid alignment fields")
    val mapped=(flag&4)==0;require(f(2)=="*"||lengths.contains(f(2)),"unknown reference");require(!mapped||(f(2)!="*"&&pos>0),"mapped read requires reference position")
    require(f(9)=="*"||f(9).matches("[A-Za-z=.]+"),"invalid SAM sequence");require(f(10)=="*"||f(9)!="*"&&f(10).length==f(9).length&&f(10).forall(c=>c>=33&&c<=126),"invalid SAM quality")
    val cigar=if(f(5)=="*")Vector.empty else "([0-9]+)([MIDNSHP=X])".r.findAllMatchIn(f(5)).map{m=>require(m.group(1).length<=10,"CIGAR length overflow");val n=m.group(1).toLong;require(n>0&&n<=2147483647L,"invalid CIGAR length");n->m.group(2).head}.toVector
    require(f(5)=="*"||cigar.map{case(n,c)=>s"$n$c"}.mkString==f(5),"invalid CIGAR")
    val query=cigar.filter(x=>"MIS=X".contains(x._2)).map(_._1).sum;val reference=cigar.filter(x=>"MDN=X".contains(x._2)).map(_._1).sum
    require(f(9)=="*"||f(5)=="*"||query==f(9).length,"CIGAR sequence length mismatch")
    if(mapped)require(cigar.nonEmpty&&reference>0&&pos-1+reference<=lengths(f(2)),"alignment outside reference")
    val aligned=cigar.filter(x=>"M=X".contains(x._2)).map(_._1).sum
    operation match{
     case "sam_flagstat"=>val primary=(flag&0x900)==0;val predicates=Vector(true,primary,(flag&0x100)!=0,(flag&0x800)!=0,(flag&0x400)!=0,mapped,primary&&(flag&1)!=0,primary&&(flag&65)==65,primary&&(flag&129)==129,primary&&(flag&3)==3&&mapped,primary&&(flag&9)==9&&mapped);val qc=if((flag&512)==0)0 else 1;predicates.indices.foreach(i=>if(predicates(i))flags(i)(qc)=Math.addExact(flags(i)(qc),1L))
     case "sam_reference_counts"=>if(f(2)=="*")unknownUnmapped+=1 else referenceCounts(f(2))(if(mapped)0 else 1)+=1
     case "sam_alignment_stats"=>if((flag&0x900)==0){recordCount+=1;if(mapped){mappedCount+=1;mapqTotal+=mapq;alignedCount+=aligned};if(f(9)!="*")baseCount+=f(9).length}
     case "sam_depth"|"sam_coverage"=>if(mapped&&(flag&0x704)==0){referenceCounts(f(2))(0)+=1;totalChars+=s.length+1;require(totalChars<=8388608,"coverage input budget exceeded");var at=pos-1;cigar.foreach{case(n,c)=>if("M=X".contains(c)){blockCount+=1;require(blockCount<=1000000,"too many coverage blocks");blocks.getOrElseUpdate(f(2),mutable.ArrayBuffer.empty)+=((at,at+n))};if("MDN=X".contains(c))at+=n}}
     case "sam_sort"|"sam_collate"=>totalChars+=s.length+1;require(totalChars<=8388608&&records.size<100000,"SAM sort buffer exceeded");records+=f
     case "sam_to_fastq"=>if((flag&0x900)==0&&f(9)!="*"){val reverse=(flag&16)!=0;val sequence=if(reverse)f(9).reverse.map(Genetics.complement(_))else f(9);val q=if(f(10)=="*")(param(p,"missing_quality").asInt+33).toChar.toString*f(9).length else if(reverse)f(10).reverse else f(10);row("@"+f(0));row(sequence);row("+");row(q)}
     case "sam_reheader"=>row(s)
     case "sam_calmd"=>if(!mapped)row(s)else{
      require(f(9)!="*"&&refs.contains(f(2))&&refs(f(2)).length.toLong==lengths(f(2)),"matching reference and SEQ required");require(!cigar.exists(_._2=='N'),"CIGAR N skips unsupported")
      val dna=refs(f(2));val sequence=f(9).toUpperCase(Locale.ROOT);val md=new java.lang.StringBuilder;var matched=0L;var nm=0L;var q=0;var at=(pos-1).toInt
      cigar.foreach{case(n,c)=>active();c match{
       case 'M'|'='|'X'=>var i=0L;while(i<n){if((i&4095)==0)active();if(sequence(q)==dna(at)&&sequence(q)!='N')matched+=1 else{md.append(matched).append(dna(at));matched=0;nm+=1};q+=1;at+=1;i+=1}
       case 'I'=>q+=n.toInt;nm+=n
       case 'D'=>md.append(matched).append('^').append(dna.substring(at,at+n.toInt));matched=0;at+=n.toInt;nm+=n
       case 'S'=>q+=n.toInt
       case 'H'|'P'=>()
       case _=>throw new IllegalArgumentException("unsupported CIGAR")
      }};md.append(matched);row((f.take(11)++f.drop(11).filterNot(x=>x.startsWith("NM:")||x.startsWith("MD:"))++Vector("NM:i:"+nm,"MD:Z:"+md)).mkString("\t"))
     }
     case _=>throw new IllegalArgumentException("unknown SAM operation")
    }
   }
  };begin()
  if(operation=="sam_flagstat"){row("category\tqc_pass\tqc_fail");flagNames.indices.foreach(i=>row(s"${flagNames(i)}\t${flags(i)(0)}\t${flags(i)(1)}"))}
  if(operation=="sam_reference_counts"){dict.foreach{case(n,l)=>row(s"$n\t$l\t${referenceCounts(n)(0)}\t${referenceCounts(n)(1)}")};row(s"*\t0\t0\t$unknownUnmapped")}
  if(operation=="sam_alignment_stats"){row("records\tmapped\tunmapped\tbases\taligned_bases\tmean_mapping_quality");row(s"$recordCount\t$mappedCount\t${recordCount-mappedCount}\t$baseCount\t$alignedCount\t${fmt(if(mappedCount==0)0d else mapqTotal.toDouble/mappedCount)}")}
  if(operation=="sam_collate"){val groups=mutable.LinkedHashMap.empty[String,mutable.ArrayBuffer[Vector[String]]];records.foreach{f=>active();groups.getOrElseUpdate(f(0),mutable.ArrayBuffer.empty)+=f};groups.values.foreach(_.foreach{f=>active();row(f.mkString("\t"))})}
  if(operation=="sam_sort"){
   val order=param(p,"order").asText;val hd=header.filter(_.startsWith("@HD\t"));require(hd.size<=1,"duplicate @HD");val fields=hd.headOption.map(_.split("\t").toVector.filterNot(_.startsWith("SO:"))).getOrElse(Vector("@HD","VN:1.6"));row((fields:+("SO:"+order)).mkString("\t"));header.filterNot(_.startsWith("@HD\t")).foreach(row)
   val sorted=if(order=="queryname")records.sortBy(_(0))else records.sortBy(f=>(ranks.getOrElse(f(2),Int.MaxValue),f(3).toLong));sorted.foreach{f=>active();row(f.mkString("\t"))}
  }
  if(operation=="sam_depth"||operation=="sam_coverage"){
   if(operation=="sam_coverage")row("reference\tlength\treads\tcovered_bases\tcoverage_percent\tmean_depth")
   var outputPositions=0L
   dict.foreach{case(name,length)=>active();val events=mutable.Map.empty[Long,Long];blocks.getOrElse(name,mutable.ArrayBuffer.empty).foreach{case(a,z)=>events(a)=events.getOrElse(a,0L)+1;events(z)=events.getOrElse(z,0L)-1};var depth=0L;var at=0L;var covered=0L;var sum=0L
    events.toVector.sortBy(_._1).foreach{case(x,d)=>active();if(depth>0){covered+=x-at;sum=Math.addExact(sum,Math.multiplyExact(x-at,depth));if(operation=="sam_depth"){outputPositions+=x-at;require(outputPositions<=1000000,"depth output exceeds 1000000 positions");var i=at;while(i<x){active();row(s"$name\t${i+1}\t$depth");i+=1}}};depth+=d;at=x}
    if(operation=="sam_coverage")row(s"$name\t$length\t${referenceCounts(name)(0)}\t$covered\t${fmt(covered*100d/length)}\t${fmt(sum.toDouble/length)}")
   }
  }
 }
}
