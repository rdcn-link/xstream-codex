package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
abstract class TableJoins(op:String) extends PairedStreams(op){
 override protected def format:String="tsv"
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def read(in:InputStream):Vector[Vector[String]]={val rows=mutable.ArrayBuffer.empty[Vector[String]];var chars=0L;var width=0;lines(in){s=>active();if(s.nonEmpty){val f=s.split("\t",-1).toVector;if(width==0)width=f.size;require(f.size==width&&width<=128,"rectangular table required");chars+=s.length+64;require(chars<=8388608&&rows.size<100000,"join buffer exceeded");rows+=f}};require(rows.nonEmpty,"nonempty table required");rows.toVector}
  val b=read(reference);val a=read(source);val ac=param(p,"source_column").asInt-1;val bc=param(p,"reference_column").asInt-1;require(ac<a.head.size&&bc<b.head.size,"join column missing");require((a++b).forall(_.nonEmpty),"row required");var count=0;var output=0L;def row(s:String):Unit={active();count+=1;output+=s.length+1;require(count<=100000&&output<=16777216,"join output budget exceeded");emit(s+"\n")}
  if(operation=="join_files_by_id"){
   require(a.forall(_(ac).nonEmpty)&&b.forall(_(bc).nonEmpty),"nonempty ID required");val groups=b.zipWithIndex.groupBy(_._1(bc));val seen=mutable.Set.empty[Int];val mode=param(p,"mode").asText
   a.foreach{x=>active();val matches=groups.getOrElse(x(ac),Vector.empty);if(matches.isEmpty&&mode!="inner")row((x++Vector.fill(b.head.size)(".")).mkString("\t"));matches.foreach{case(y,i)=>seen+=i;row((x++y).mkString("\t"))}}
   if(mode=="full")b.zipWithIndex.filterNot(x=>seen(x._2)).foreach{case(y,_)=>row((Vector.fill(a.head.size)(".")++y).mkString("\t"))}
  }else{
   require(a.size.toLong*b.size<=16000000,"numeric join comparison budget exceeded");def number(s:String):Double={val v=s.toDouble;require(v.isFinite&&math.abs(v)<=1e12,"bounded finite key required");v};val keys=b.map(x=>number(x(bc)));val tolerance=param(p,"distance_x1000").asInt/1000d;val closest=param(p,"closest").asBoolean
   a.foreach{x=>active();val key=number(x(ac));var matches=Vector.empty[(Int,Double)];var best=Double.PositiveInfinity;keys.zipWithIndex.foreach{case(v,i)=>if((i&1023)==0)active();val distance=math.abs(key-v);if(distance<=tolerance){if(!closest)matches:+=i->distance else if(distance<best){matches=Vector(i->distance);best=distance}}};matches.foreach{case(i,d)=>row((x++b(i)).mkString("\t")+"\t"+String.format(Locale.ROOT,"%.6f",Double.box(d)))}}
  }
 }
}
abstract class SequenceInsertion(op:String) extends PairedStreams(op) with DnaFasta{
 override protected def format:String="fasta"
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def read(in:InputStream):(String,String)={var records=Vector.empty[(String,String)];fasta(in){(h,s)=>records:+=h->s};require(records.size==1,"exactly one DNA sequence required");records.head}
  val(h,s)=read(source);val(_,r)=read(reference);val at=param(p,"position").asInt;require(at<=s.length,"insertion position outside sequence");emit(">"+h+"\n"+s.take(at)+r+s.drop(at)+"\n")
 }
}
abstract class CodonProjection(op:String) extends PairedStreams(op){
 override protected def format:String="fasta"
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def read(in:InputStream,protein:Boolean):Vector[(String,String)]={val records=mutable.ArrayBuffer.empty[(String,String)];val sequence=new java.lang.StringBuilder;var header:Option[String]=None;var total=0L
   def flush():Unit=header.foreach{h=>require(sequence.length>0&&records.size<10000,"nonempty sequence and record budget required");records+=h->sequence.toString}
   lines(in){s=>active();total+=s.length;require(total<=1000000,"alignment input budget exceeded");if(s.startsWith(">")){flush();val h=s.drop(1).takeWhile(!_.isWhitespace);require(h.nonEmpty,"sequence ID required");header=Some(h);sequence.setLength(0)}else if(s.nonEmpty){require(header.nonEmpty&&s.forall(c=>if(protein)"ACDEFGHIKLMNPQRSTVWY*-".contains(c.toUpper)else"ACGT".contains(c.toUpper)),"invalid sequence");sequence.append(s.toUpperCase(Locale.ROOT))}};flush();require(records.nonEmpty&&records.map(_._1).distinct.size==records.size,"unique nonempty records required");records.toVector}
  val dna=read(source,false);val proteins=read(reference,true).toMap;require(proteins.values.map(_.length).toSet.size==1,"aligned proteins must have equal width");require(dna.map(_._1).toSet==proteins.keySet,"protein and DNA IDs differ");dna.foreach{case(id,seq)=>active();require(seq.length%3==0,"complete DNA codons required");val codons=seq.grouped(3).toVector;var index=0;val out=new java.lang.StringBuilder;proteins(id).foreach{aa=>active();if(aa=='-')out.append("---")else{require(index<codons.size&&Genetics.codons(codons(index))==aa,"protein/codon mismatch");out.append(codons(index));index+=1};require(out.length<=3000000,"aligned output budget exceeded")};require(index==codons.size,"unused DNA codons");emit(">"+id+"\n"+out.toString+"\n")}
 }
}
abstract class ProteinPresentation(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(operation=="emboss_pepwheel")"svg"else"tsv"
 protected def run(in:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def fmt(v:Double)=String.format(Locale.ROOT,"%.6f",Double.box(if(math.abs(v)<.0000005)0d else v));def row(s:String):Unit=emit(s+"\n")
  val alphabet=ProteinProperties.alphabet
  // Wimley-White whole-residue transfer energies (kcal/mol): charged D/E and neutral H; EMBOSS Ewhite-wimley table.
  val octanol=alphabet.zip(Vector(.50,-.02,3.65,3.65,-1.71,1.15,.11,-1.12,2.80,-1.25,-.67,.85,.14,.77,1.81,.46,.25,-.46,-2.09,-.71)).toMap
  val interface=alphabet.zip(Vector(.17,-.24,1.23,2.02,-1.13,.01,.17,-.31,.99,-.56,-.23,.42,.45,.58,.81,.13,.14,.07,-.85,-.94)).toMap
  var header:Option[String]=None;val seq=new java.lang.StringBuilder;var records=0;var windows=0
  if(operation=="emboss_octanol")row("sequence\tstart\tend\toctanol\tinterface\tdifference")
  def flush():Unit=header.foreach{h=>records+=1;require(records<=10000&&seq.length>0,"nonempty protein required");val s=seq.toString;val id=h.takeWhile(!_.isWhitespace)
   if(operation=="emboss_pepwheel"){require(records==1&&s.length<=36,"wheel requires one protein of1..36 residues");emit("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 400 400\"><circle cx=\"200\" cy=\"200\" r=\"150\" fill=\"none\" stroke=\"#94a3b8\"/>");val used=mutable.Map.empty[Int,Int];s.zipWithIndex.foreach{case(c,i)=>active();val degrees=i*param(p,"angle").asInt%360;val ring=used.getOrElse(degrees,0);used(degrees)=ring+1;val radius=150-ring*3;val a=degrees*math.Pi/180;val x=200+radius*math.cos(a);val y=200+radius*math.sin(a);emit(s"<circle cx=\"${fmt(x)}\" cy=\"${fmt(y)}\" r=\"13\" fill=\"#cffafe\"/><text x=\"${fmt(x)}\" y=\"${fmt(y+4)}\" text-anchor=\"middle\" font-size=\"12\">$c</text>")};row("</svg>")}
   else{val width=param(p,"window").asInt;if(s.length>=width){var oct=s.take(width).map(octanol).sum;var inter=s.take(width).map(interface).sum;(0 to s.length-width).foreach{i=>active();if(i>0){oct+=octanol(s(i+width-1))-octanol(s(i-1));inter+=interface(s(i+width-1))-interface(s(i-1))};windows+=1;require(windows<=100000,"window output budget exceeded");row(s"$id\t${i+1}\t${i+width}\t${fmt(oct/width)}\t${fmt(inter/width)}\t${fmt((oct-inter)/width)}")}}}
  }
  lines(in){s=>active();if(s.startsWith(">")){flush();require(s.drop(1).trim.nonEmpty && !s.contains('\t'),"valid protein header required");header=Some(s.drop(1));seq.setLength(0)}else if(s.nonEmpty){require(header.nonEmpty&&s.forall(c=>alphabet.contains(c.toUpper)),"standard20 protein required");require(seq.length.toLong+s.length<=100000,"protein record budget exceeded");seq.append(s.toUpperCase(Locale.ROOT))}};flush();require(records>0,"FASTA required")
 }
}
