package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

abstract class SeqtkOperators(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(Set("seqtk_comp","seqtk_listhet","seqtk_hety","seqtk_fqchk")(operation))"tsv"else if(operation=="seqtk_telo")"bed"else if(Set("seqtk_trimfq","seqtk_dropse")(operation))"fastq"else"fasta"
 override protected def validate(p:JsonNode):Unit={super.validate(p)
  if(operation=="seqtk_hety")require(param(p,"starts").asInt<=param(p,"window").asInt,"window starts exceed window size")
  if(operation=="seqtk_telo"){val m=param(p,"motif").asText;require(m.nonEmpty&&m.length<=31&&m.forall("ACGTacgt".contains(_)),"motif requires 1..31 unambiguous DNA bases")}
  if(operation=="seqtk_subseq")regions(p)
 }
 private def regions(p:JsonNode):Vector[(String,Int,Int)]=param(p,"regions").elements.asScala.map{v=>val x=v.asText.split(":",-1);require(x.length==3&&x(0).nonEmpty,"region must be ID:start:end");val a=x(1).toInt;val z=x(2).toInt;require(a>=0&&z>a,"invalid region interval");(x(0),a,z)}.toVector
 protected def run(input:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def n(k:String)=param(p,k).asInt;def b(k:String)=param(p,k).asBoolean;def t(k:String)=param(p,k).asText
  def row(s:String):Unit=emit(s+"\n");def fmt(x:Double,d:Int=1):String=String.format(Locale.ROOT,"%."+d+"f",Double.box(x))
  case class Read(title:String,seq:String,qual:Option[String]=None){val id=title.takeWhile(!_.isWhitespace);def write():Unit={row((if(qual.nonEmpty)"@"else">")+title);row(seq);qual.foreach{q=>row("+");row(q)}}}
  val rng=new java.util.Random(n("seed").toLong);var seen=0L;var budget=0L
  val reservoir=mutable.ArrayBuffer.empty[Read];var buffered=0L;var pending:Option[Read]=None
  val quality=mutable.ArrayBuffer.empty[Array[Long]];val bases=mutable.ArrayBuffer.empty[Array[Long]]
  def tick():Unit={budget+=1;require(budget<=64000000,"sequence comparison budget exceeded");if((budget&4095)==0)active()}
  if(operation=="seqtk_comp")row("#chr\tlength\t#A\t#C\t#G\t#T\t#2\t#3\t#4\t#CpG\t#tv\t#ts\t#CpG-ts")
  if(operation=="seqtk_listhet")row("#chr\tposition\tbase")
  def accept(r:Read):Unit={
   active();budget=0;require(r.title.nonEmpty&&r.seq.nonEmpty&&r.seq.forall(c=>Genetics.iupac.contains(c.toUpper)),"nonempty nucleotide sequence and title required")
   val seq=r.seq.toUpperCase(Locale.ROOT)
   operation match{
    case "seqtk_comp"=>val counts=Array.fill(11)(0L)
     seq.indices.foreach{i=>tick();val c=seq(i);val options=Genetics.iupac(c);val size=options.length
      if(size==1)counts("ACGT".indexOf(options.head))+=1 else counts(size+2)+=1
      if("RY".contains(c))counts(9)+=1 else if(size==2)counts(8)+=1
      val cpg=("CY".contains(c)&&i+1<seq.length&&"GR".contains(seq(i+1)))||("GR".contains(c)&&i>0&&"CY".contains(seq(i-1)))
      if(cpg){counts(7)+=1;if("RY".contains(c))counts(10)+=1}
     };row((Vector(r.id,seq.length.toString)++counts.map(_.toString)).mkString("\t"))
    case "seqtk_listhet"=>r.seq.indices.foreach{i=>tick();if(Genetics.iupac(seq(i)).length==2)row(s"${r.id}\t${i+1}\t${r.seq(i)}")}
    case "seqtk_randbase"=>val resolved=r.seq.map{c=>tick();val options=Genetics.iupac(c.toUpper);if(options.length==2){val x=options(rng.nextInt(2));if(c.isLower)x.toLower else x}else c};r.copy(seq=resolved).write()
    case "seqtk_sample"=>seen+=1;val at=if(seen<=n("count"))seen-1 else(rng.nextDouble()*seen).toLong
     if(at<n("count")){val chars=r.title.length.toLong+r.seq.length+64;if(at<reservoir.size){buffered-=reservoir(at.toInt).title.length.toLong+reservoir(at.toInt).seq.length+64;reservoir(at.toInt)=r}else reservoir+=r;buffered+=chars;require(buffered<=8388608,"reservoir exceeds 8 MiB characters")}
    case "seqtk_subseq"=>regions(p).filter(_._1==r.id).foreach{case(_,a,z)=>require(z<=seq.length,"region outside sequence");r.copy(title=s"${r.id}:${a+1}-$z",seq=r.seq.substring(a,z)).write()}
    case "seqtk_seq"=>if(seq.length>=n("minimum_length")&&(!b("drop_ambiguous")||seq.forall("ACGT".contains(_)))){
     val out=if(b("uppercase"))seq else r.seq;row(">"+(if(b("drop_comments"))r.id else r.title));if(n("line_width")==0)row(out)else out.grouped(n("line_width")).foreach(x=>{active();row(x)})
    }
    case "seqtk_cutN"=>var k=0;var search=0;var done=false
     while(!done){var found:Option[(Int,Int)]=None
      while(search<seq.length&&found.isEmpty){tick();if(seq(search)=='N'){
       var score=0L;var best= -1L;var end=search;var i=search
       while(i<seq.length&&score>=0){tick();score+= (if(seq(i)=='N')1 else -n("penalty"));if(score>best){best=score;end=i};i+=1}
       score=0;best= -1;var start=end;i=end
       while(i>=0&&score>=0){tick();score+=(if(seq(i)=='N')1 else -n("penalty"));if(score>best){best=score;start=i};i-=1}
       search=end+1;if(end+1-start>=n("minimum_tract"))found=Some((start,end+1))
      }else search+=1}
      found match{case Some((a,z))=>if(a>k)r.copy(title=s"${r.id}:${k+1}-$a",seq=r.seq.substring(k,a)).write();k=z;search=z
       case None=>if(k<seq.length)r.copy(title=s"${r.id}:${k+1}-${seq.length}",seq=r.seq.substring(k)).write();done=true}
     }
    case "seqtk_hety"=>val win=n("window");val step=win/n("starts");var next=0;var i=math.min(win,seq.length)
     while(i<=seq.length){val start=if(i==seq.length&&seq.length>=win)math.max(i-win,next)else math.max(0,i-win);var callable=0L;var het=0L
      (start until i).foreach{j=>tick();val size=if(b("mask_lowercase")&&r.seq(j).isLower)4 else Genetics.iupac(seq(j)).length;if(size<=2){callable+=1;if(size==2)het+=1}}
      if(callable>0)row(s"${r.id}\t$next\t$i\t${fmt(het.toDouble/callable*win,2)}\t$callable\t$het");next=i
      if(i==seq.length)i=seq.length+1 else i=math.min(seq.length,((i/step)+1)*step)
     }
    case "seqtk_telo"=>val motif=t("motif").toUpperCase(Locale.ROOT);val rotations=motif.indices.map(i=>motif.drop(i)+motif.take(i)).toSet;val len=motif.length
     def scan(reverse:Boolean,stop:Int):(Long,Int)={var score=0L;var best=0L;var bestAt= -1;var offset=0;var continue=true
      while(offset<seq.length-stop&&continue){tick();val i=if(reverse)seq.length-1-offset else offset
       val hit=if(offset+1<len)false else{val word=if(reverse)seq.substring(i,i+len).reverse.map(c=>Genetics.complement(c))else seq.substring(i-len+1,i+1);rotations(word)}
       if(if(reverse)offset+1>=len else offset>=len)score+=(if(hit)1 else -n("penalty"))
       if(score>best){best=score;bestAt=i}else if(best-score>n("max_drop"))continue=false;offset+=1
      };(best,bestAt)
     }
     val left=scan(false,0);val start=if(left._1>=n("minimum_score")){row(s"${r.id}\t0\t${left._2+1}\t${seq.length}");left._2+1}else 0
     val right=scan(true,start);if(right._1>=n("minimum_score"))row(s"${r.id}\t${right._2}\t${seq.length}\t${seq.length}")
    case "seqtk_trimfq"=>val q=r.qual.get;var begin=0;var end=q.length
     if(n("left")>0||n("right")>0){begin=n("left");end=q.length-n("right")}
     else if(q.length>n("minimum_length")){
      var score=0d;var best=0d;var temporary=0
      q.indices.foreach{i=>tick();val quality=math.max(3,q(i)-33);score+=n("error_per_thousand")/1000d-math.pow(10,-quality/10d);if(score>best){best=score;begin=temporary;end=i+1};if(score<0){score=0;temporary=i+1}}
      if(best==0){begin=0;end=n("minimum_length")}
      if(end-begin<n("minimum_length")){val len=n("minimum_length");var sum=q.take(len).map(_-33).sum;var highest=sum;begin=0;(len until q.length).foreach{i=>tick();sum+=q(i)-q(i-len);if(sum>highest){highest=sum;begin=i-len+1}};end=begin+len}
     };if(begin<end)r.copy(seq=r.seq.substring(begin,end),qual=Some(q.substring(begin,end))).write()
    case "seqtk_dropse"=>pending match{case None=>pending=Some(r);case Some(a)=>def base(id:String)=if(id.length>2&&id(id.length-2)=='/'&&id.last.isDigit)id.dropRight(2)else id
     if(a.id.length==r.id.length&&base(a.id)==base(r.id)){a.write();r.write();pending=None}else pending=Some(r)}
    case "seqtk_fqchk"=>require(seq.length<=10000,"QC limited to 10000 positions");while(quality.size<seq.length){quality+=Array.fill(94)(0L);bases+=Array.fill(5)(0L)};seq.indices.foreach{i=>tick();quality(i)(r.qual.get(i)-33)+=1;val base="ACGT".indexOf(seq(i));bases(i)(if(base<0)4 else base)+=1}
    case _=>throw new IllegalArgumentException("unsupported seqtk adapter")
   }
  }
  val fastq=Set("seqtk_trimfq","seqtk_dropse","seqtk_fqchk")(operation);var block=Vector.empty[String];var title:Option[String]=None;val seq=new java.lang.StringBuilder
  def flush():Unit=title.foreach(x=>accept(Read(x,seq.toString)))
  lines(input){s=>if(fastq){block=block:+s;if(block.size==4){require(block(0).startsWith("@")&&block(0).length>1&&block(2).startsWith("+")&&(block(2)=="+"||block(2).drop(1)==block(0).drop(1)),"invalid FASTQ titles");require(block(1).length==block(3).length&&block(3).forall(c=>c>=33&&c<=126),"invalid FASTQ quality");accept(Read(block(0).drop(1),block(1),Some(block(3))));block=Vector.empty}}
   else if(s.startsWith(">")){flush();title=Some(s.drop(1));seq.setLength(0)}else if(s.nonEmpty){require(title.nonEmpty,"sequence before FASTA title");val text=s.filterNot(_.isWhitespace);require(seq.length.toLong+text.length<=8388608,"sequence exceeds 8 MiB characters");seq.append(text)}
  };require(block.isEmpty,"truncated FASTQ record");if(!fastq)flush()
  if(operation=="seqtk_sample")reservoir.foreach(r=>{active();r.write()})
  if(operation=="seqtk_fqchk"){
   row("POS\t#bases\t%A\t%C\t%G\t%T\t%N\tavgQ\terrQ\t%low\t%high")
   def report(label:String,h:Array[Long],c:Array[Long]):Unit={active();val total=h.sum;if(total>0){val mean=h.indices.map(i=>i*h(i).toDouble).sum/total;val error= -10*math.log10(h.indices.map(i=>h(i)* (if(i<=3).5 else math.pow(10,-i/10d))).sum/total);val low=h.take(n("quality_threshold")).sum*100d/total;row((Vector(label,total.toString)++c.map(x=>fmt(x*100d/total))++Vector(fmt(mean),fmt(error),fmt(low),fmt(100-low))).mkString("\t"))}}
   val h=Array.fill(94)(0L);val c=Array.fill(5)(0L);quality.indices.foreach{i=>quality(i).indices.foreach(j=>h(j)+=quality(i)(j));bases(i).indices.foreach(j=>c(j)+=bases(i)(j))};report("ALL",h,c);quality.indices.foreach(i=>report((i+1).toString,quality(i),bases(i)))
  }
 }
}
