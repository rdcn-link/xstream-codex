package xstream.galaxy
import java.io.InputStream
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Bounded VCF text transforms; no filesystem, process or network access. */
abstract class VariantStreams(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(Set("vcf_list_samples","vcf_genotype_dosage","vcf_variant_counts","vcf_genotype_table")(operation))"tsv"else"vcf"
 override protected def validate(p:JsonNode):Unit={super.validate(p)
  if(operation=="vcf_select_samples"||operation=="vcf_remove_annotations"||operation=="vcf_rename_samples"){
   val key=if(operation=="vcf_select_samples")"samples"else if(operation=="vcf_rename_samples")"renames"else"tags"
   val values=param(p,key).elements.asScala.map(_.asText).toVector
   require(values.nonEmpty&&values.distinct.size==values.size&&values.forall(x=>x.nonEmpty && !x.exists(_.isWhitespace)),"unique nonempty names required")
   if(key=="tags")require(values.forall(_.matches("[A-Za-z_][A-Za-z0-9_.]*")),"invalid INFO tag")
   if(key=="renames")require(values.forall(x=>x.count(_=='=')==1&&x.split("=",-1).forall(_.nonEmpty)),"renames must be old=new")
  }
 }
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n")
  def values(k:String)=param(p,k).elements.asScala.map(_.asText).toVector
  var samples=Vector.empty[String];var header=false;var fileformat=false;var chosen=Vector.empty[Int];var count=0L;val counts=Array.fill[Long](4)(0L);var metadata=0L
  val textOutput=format=="vcf"
  val tags=if(operation=="vcf_remove_annotations")values("tags").toSet else Set.empty[String]
  val renames=if(operation=="vcf_rename_samples")values("renames").map{x=>val a=x.split("=",-1);a(0)->a(1)}else Vector.empty
  require(renames.map(_._1).distinct.size==renames.size,"duplicate old sample name")
  lines(input){s=>active()
   if(s.startsWith("##")){
    require(!header,"metadata after VCF header");metadata+=s.length+1;require(metadata<=8388608,"VCF metadata budget exceeded")
    if(s.startsWith("##fileformat=")){require(!fileformat&&s.matches("##fileformat=VCFv4\\.[0-5]"),"unsupported or duplicate VCF version");fileformat=true}
    val removed=tags.exists(k=>s.startsWith("##INFO=<ID="+k+",")) || (operation=="vcf_fill_allele_counts"&&Vector("AN","AC").exists(k=>s.startsWith("##INFO=<ID="+k+","))) || (operation=="vcf_filter_quality"&&s.startsWith("##FILTER=<ID=LowQual,"))
    if(textOutput && !removed)row(s)
   }else if(s.startsWith("#CHROM\t")){
    require(fileformat && !header,"one VCF header after fileformat required");val f=s.split("\t",-1).toVector
    require(f.take(8)==Vector("#CHROM","POS","ID","REF","ALT","QUAL","FILTER","INFO")&&(f.size==8||f.size>=10&&f(8)=="FORMAT"),"invalid VCF header")
    samples=f.drop(9);require(samples.size<=1024&&samples.distinct.size==samples.size&&samples.forall(x=>x.nonEmpty && !x.exists(_.isWhitespace)),"invalid samples");header=true
    if(operation=="vcf_select_samples"){chosen=values("samples").map{x=>val i=samples.indexOf(x);require(i>=0,"unknown sample: "+x);i};row((f.take(9)++chosen.map(samples(_))).mkString("\t"))}
    else if(operation=="vcf_rename_samples"){require(renames.forall(x=>samples.contains(x._1)),"unknown sample rename");val map=renames.toMap;val updated=samples.map(x=>map.getOrElse(x,x));require(updated.distinct.size==updated.size,"duplicate renamed samples");row((f.take(9)++updated).mkString("\t"))}
    else if(operation=="vcf_list_samples")samples.foreach(row)
    else if(operation=="vcf_genotype_dosage")row((Vector("CHROM","POS","REF","ALT")++samples).mkString("\t"))
    else if(operation=="vcf_genotype_table")row("CHROM\tPOS\tREF\tALT\tSAMPLE\tGT")
    else if(textOutput){
     if(operation=="vcf_fill_allele_counts"){row("##INFO=<ID=AN,Number=1,Type=Integer,Description=\"Total called alleles\">");row("##INFO=<ID=AC,Number=A,Type=Integer,Description=\"Alternate allele counts\">")}
     if(operation=="vcf_filter_quality")row("##FILTER=<ID=LowQual,Description=\"Below configured minimum QUAL\">")
     row(s)
    }
   }else{
    require(header&&s.nonEmpty && !s.startsWith("#"),"VCF record requires header")
    val f=s.split("\t",-1).toArray;require(f.length==(if(samples.isEmpty)8 else 9+samples.size),"VCF record width mismatch")
    require(f(0).nonEmpty && !f(0).exists(_.isWhitespace)&&f(1).matches("[0-9]{1,10}")&&f(1).toLong>=1&&f(1).toLong<=2147483647L,"invalid VCF position")
    require(f(3).matches("[ACGTNacgtn]+"),"invalid reference allele")
    val alts=f(4).split(",",-1).toVector;require(alts.nonEmpty&&alts.distinct.size==alts.size&&alts.forall(x=>x=="."||x=="*"||x.matches("[ACGTNacgtn]+")||x.matches("<[A-Za-z0-9_:]+>")),"unsupported ALT allele")
    require(f(5)=="."||{val q=f(5).toDouble;q.isFinite&&q>=0},"invalid QUAL")
    val info=if(f(7)==".")Vector.empty else f(7).split(";",-1).toVector
    require(info.forall(x=>x.nonEmpty&&x.takeWhile(_!='=').matches("[A-Za-z_][A-Za-z0-9_.]*"))&&info.map(_.takeWhile(_!='=')).distinct.size==info.size,"invalid or duplicate INFO")
    val keys=if(samples.isEmpty)Vector.empty else f(8).split(":",-1).toVector
    require(keys.distinct.size==keys.size&&keys.forall(_.matches("[A-Za-z_][A-Za-z0-9_]*")),"invalid FORMAT")
    val gt=keys.indexOf("GT");val calls=samples.indices.map{i=>val fields=f(9+i).split(":",-1).toVector;require(fields.size<=keys.size&&fields.forall(_.nonEmpty),"invalid sample fields");fields.padTo(keys.size,".")}.toVector
    val genotypes=if(gt<0)Vector.empty else calls.map{c=>val raw=c(gt);require(raw.matches("(?:[0-9]+|\\.)(?:[/|](?:[0-9]+|\\.)){0,7}") && !(raw.contains('/')&&raw.contains('|')),"invalid GT");val a=raw.split("[/|]",-1).toVector;require(a.forall(x=>x=="."||x.length<=3&&x.toInt<= (if(alts==Vector("."))0 else alts.size)),"GT allele outside ALT range");a}
    if(Set("vcf_genotype_dosage","vcf_fill_allele_counts","vcf_missing_to_ref","vcf_set_missing_genotypes","vcf_fix_ploidy","vcf_genotype_table")(operation))require(gt>=0,"GT FORMAT required")
    operation match{
     case "vcf_list_samples"=>()
     case "vcf_variant_counts"=>count=Math.addExact(count,1L);val types=alts.map{a=>if(a=="."||a=="*"||a.startsWith("<"))3 else if(a.length!=f(3).length)1 else if(a.length==1)0 else 2}.toSet;types.foreach(i=>counts(i)=Math.addExact(counts(i),1L))
     case "vcf_genotype_table"=>samples.indices.foreach{i=>row((Vector(f(0),f(1),f(3),f(4),samples(i),calls(i)(gt))).mkString("\t"))}
     case "vcf_genotype_dosage"=>row((Vector(f(0),f(1),f(3),f(4))++genotypes.map(a=>if(a.contains("."))"."else a.count(_!="0").toString)).mkString("\t"))
     case "vcf_select_samples"=>row((f.take(9).toVector++chosen.map(i=>f(9+i))).mkString("\t"))
     case "vcf_fill_allele_counts"=>val ac=Array.fill[Long](if(alts==Vector("."))0 else alts.size)(0L);var an=0L;genotypes.flatten.filter(_!=".").foreach{x=>an+=1;val i=x.toInt;if(i>0)ac(i-1)+=1};f(7)=(info.filterNot(x=>Set("AN","AC")(x.takeWhile(_!='=')))++Vector("AN="+an,"AC="+(if(ac.isEmpty)"0"else ac.mkString(",")))).mkString(";");row(f.mkString("\t"))
     case "vcf_remove_annotations"=>val kept=info.filterNot(x=>tags(x.takeWhile(_!='=')));f(7)=if(kept.isEmpty)"."else kept.mkString(";");row(f.mkString("\t"))
     case "vcf_filter_quality"=>if(f(5)=="."||f(5).toDouble<param(p,"minimum_quality").asInt){val filters=f(6).split(";").filterNot(x=>x=="PASS"||x=="."||x=="LowQual");f(6)=(filters.toVector:+"LowQual").mkString(";")};row(f.mkString("\t"))
     case "vcf_missing_to_ref"|"vcf_set_missing_genotypes"|"vcf_fix_ploidy"=>samples.indices.foreach{i=>val a=genotypes(i);val separator=if(calls(i)(gt).contains('|'))"|"else"/";val updated=operation match{case "vcf_missing_to_ref"=>a.map(x=>if(x==".")"0"else x);case "vcf_set_missing_genotypes"=>a.map(_=>".");case _=>a.take(param(p,"ploidy").asInt).padTo(param(p,"ploidy").asInt,a.last)};f(i+9)=calls(i).updated(gt,updated.mkString(separator)).mkString(":")};row(f.mkString("\t"))
     case "vcf_rename_samples"=>row(s)
     case _=>throw new IllegalArgumentException("unknown variant operation")
    }
   }
  }
  require(header,"missing VCF header")
  if(operation=="vcf_variant_counts"){row("samples\tsites\tsnps\tindels\tmnps\tother");row((Vector(samples.size.toLong,count)++counts.toVector).mkString("\t"))}
 }
}
