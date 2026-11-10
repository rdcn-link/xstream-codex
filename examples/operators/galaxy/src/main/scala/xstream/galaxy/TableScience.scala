package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

abstract class TableScience(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(Set("gff_to_bedgraph","circos_gc_skew")(operation))"bedgraph"else"tsv"
 override protected def validate(p:JsonNode):Unit={super.validate(p)
  if(operation=="correlation_matrix"){val c=param(p,"columns").elements.asScala.map(_.asInt).toVector;require(c.size>=2&&c.distinct==c,"at least two unique columns required")}
  if(operation=="category_totals")categoryConfig(p)
 }
 private def categoryConfig(p:JsonNode):Vector[(String,Vector[String])]={
  val c=param(p,"categories").elements.asScala.map{v=>val at=v.asText.indexOf('=');require(at>0,"category must be name=member,member");val name=v.asText.take(at);val members=v.asText.drop(at+1).split(",",-1).toVector;require(members.forall(x=>x.nonEmpty && !x.exists(_.isWhitespace))&& !name.exists(_.isWhitespace),"invalid category or member");name->members}.toVector
  val all=c.flatMap(_._2);require(c.nonEmpty&&c.map(_._1).distinct.size==c.size&&all.distinct.size==all.size,"categories and member assignments must be unique");c
 }
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def n(k:String)=param(p,k).asInt;def t(k:String)=param(p,k).asText;def row(s:String):Unit=emit(s+"\n")
  def fmt(x:Double):String={require(x.isFinite,"nonfinite analysis result");String.format(Locale.ROOT,"%.6f",Double.box(if(x==0)0d else x))}
  def numeric(s:String):Double={require(s.length<=128,"numeric field too long");val v=s.toDouble;require(v.isFinite&&math.abs(v)<=1e100,"finite magnitude <=1e100 required");v}
  def decimal(s:String):BigDecimal={val v=numeric(s);BigDecimal(s)}
  val rows=mutable.ArrayBuffer.empty[Vector[String]];var chars=0L;var width= -1
  def store(f:Vector[String],s:String):Unit={chars+=s.length+64;require(chars<=8388608&&rows.size<100000,"table buffer exceeded");rows+=f}
  var title:Option[String]=None;val sequence=new java.lang.StringBuilder
  def fasta():Unit=title.foreach{h=>val seq=sequence.toString.toUpperCase(Locale.ROOT);require(seq.forall(Genetics.iupac.contains),"invalid nucleotide");seq.grouped(n("window")).zipWithIndex.foreach{case(window,i)=>active();val g=window.count(_=='G');val c=window.count(_=='C');val skew=if(g+c==0)0d else(g-c).toDouble/(g+c);row(s"${h.takeWhile(!_.isWhitespace)}\t${i.toLong*n("window")}\t${math.min(seq.length.toLong,(i.toLong+1)*n("window"))}\t${fmt(skew)}")}}
  val categories=if(operation=="category_totals")categoryConfig(p)else Vector.empty;val membership=categories.zipWithIndex.flatMap{case((_,members),i)=>members.map(_->i)}.toMap
  var criteria=Vector.empty[String];var totals=Array.empty[Array[BigDecimal]]
  lines(input){s=>
   active()
   if(operation=="circos_gc_skew"){
    if(s.startsWith(">")){fasta();require(s.drop(1).trim.nonEmpty,"missing FASTA title");title=Some(s.drop(1));sequence.setLength(0)}else if(s.nonEmpty){require(title.nonEmpty,"sequence before FASTA title");val value=s.filterNot(_.isWhitespace);require(sequence.length.toLong+value.length<=8388608,"sequence exceeds 8 MiB characters");sequence.append(value)}
   }else if(s.nonEmpty && !s.startsWith("#")){
    val f=s.split("\t",-1).toVector
    operation match{
     case "reverse_columns"=>row(f.reverse.mkString("\t"))
     case "transpose_table"|"annovar_replace_na"=>if(width<0)width=f.size;require(f.size==width,"rectangular table required");if(operation=="transpose_table")require(width<=10000,"transpose width exceeds 10000");store(f,s)
     case "numeric_summary"=>require(n("column")<=f.size,"numeric column missing");numeric(f(n("column")-1));store(Vector(f(n("column")-1)),s)
     case "correlation_matrix"=>val c=param(p,"columns").elements.asScala.map(_.asInt-1).toVector;require(c.forall(_<f.size),"correlation column missing");val values=c.map(f(_));values.foreach(numeric);store(values,s)
     case "gff_to_bedgraph"=>require(f.size==9,"nine-column GFF required");val a=f(3).toLong;val z=f(4).toLong;require(a>=1&&z>=a&&z<=2147483647L&&f(0).nonEmpty,"invalid GFF coordinates");row(s"${f(0)}\t${a-1}\t$z\t0")
     case "circos_cytobands"=>require(f.size>=9,"BED9 required");val a=f(1).toLong;val z=f(2).toLong;require(a>=0&&z>a&&z<=2147483647L,"invalid BED interval");require(Vector(f(0),f(3)).forall(x=>x.nonEmpty && !x.exists(_.isWhitespace)),"invalid band name");val rgb=f(8).split(",",-1);require(rgb.length==3&&rgb.forall(x=>x.matches("[0-9]{1,3}")&&x.toInt<=255),"RGB requires three 0..255 integers");row(s"band ${f(0)} ${f(3)} ${f(3)} $a $z ${rgb.map(_.toInt).mkString(",")}")
     case "category_totals"=>if(criteria.isEmpty){require(f.size<=128&&f.distinct==f&&f.forall(_.nonEmpty),"unique nonempty criterion names required");criteria=f;totals=Array.fill(categories.size,criteria.size)(BigDecimal(0))}
      else{require(f.size==criteria.size+1&&membership.contains(f(0)),"unknown member or wrong criterion width");val group=membership(f(0));f.drop(1).zipWithIndex.foreach{case(x,i)=>val v=decimal(x);if(v>0)totals(group)(i)+=v}}
     case _=>throw new IllegalArgumentException("unsupported table analysis")
    }
   }
  }
  if(operation=="circos_gc_skew")fasta()
  if(operation=="transpose_table"&&width>0)(0 until width).foreach{i=>active();row(rows.map(_(i)).mkString("\t"))}
  if(operation=="annovar_replace_na"&&rows.nonEmpty){val known=Set("1000g2012apr_all","esp6500si_all");val numerical=(0 until width).map{i=>val nonmissing=rows.map(_(i)).filter(_!="NA");val numbers=nonmissing.count(x=>try{x.toDouble;true}catch{case _:NumberFormatException=>false});known(rows.head(i))||numbers>=nonmissing.size-numbers};rows.foreach{f=>active();row(f.zipWithIndex.map{case(v,i)=>if(v=="NA"&&numerical(i))"-1"else v}.mkString("\t"))}}
  if(operation=="numeric_summary"){
   require(rows.nonEmpty,"numeric summary requires data");val v=rows.map(x=>numeric(x.head)).sorted;val mean=v.sum/v.size;val variance=v.map(x=>(x-mean)*(x-mean)).sum;val median=if(v.size%2==1)v(v.size/2)else(v(v.size/2-1)+v(v.size/2))/2
   row("count\tsum\tmin\tmax\tmean\tmedian\tpopulation_sd\tsample_sd");row((Vector(v.size.toString)++Vector(v.sum,v.head,v.last,mean,median,math.sqrt(variance/v.size)).map(fmt)++Vector(if(v.size<2)"NA"else fmt(math.sqrt(variance/(v.size-1))))).mkString("\t"))
  }
  if(operation=="correlation_matrix"){
   require(rows.size>=2,"correlation requires at least two rows");val size=rows.head.size
   def ranks(v:Vector[Double]):Vector[Double]={val order=v.indices.sortBy(v(_));val result=Array.fill(v.size)(0d);var i=0;while(i<order.size){active();var j=i+1;while(j<order.size&&v(order(j))==v(order(i)))j+=1;val rank=(i+j-1)/2d+1;(i until j).foreach(k=>result(order(k))=rank);i=j};result.toVector}
   val columns=(0 until size).map{i=>val c=rows.map(x=>numeric(x(i))).toVector;if(t("method")=="spearman")ranks(c)else c}
   val centered=columns.map{c=>val mean=c.sum/c.size;c.map(_-mean)};val norms=centered.map(c=>math.sqrt(c.map(x=>x*x).sum));require(norms.forall(x=>x>0&&x.isFinite),"constant columns have undefined correlation")
   (0 until size).foreach{i=>active();row((0 until size).map{j=>val dot=centered(i).zip(centered(j)).map{case(a,b)=>a*b}.sum;fmt(math.max(-1,math.min(1,dot/norms(i)/norms(j))))}.mkString("\t"))}
  }
  if(operation=="category_totals"&&criteria.nonEmpty){row((Vector("category")++criteria).mkString("\t"));categories.indices.foreach{i=>active();row((Vector(categories(i)._1)++totals(i).map(_.bigDecimal.stripTrailingZeros.toPlainString)).mkString("\t"))}}
 }
}
