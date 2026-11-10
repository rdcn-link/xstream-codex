package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable

abstract class AlignmentScience(op:String) extends PairedStreams(op){
 override protected def format:String=if(Set("emboss_needle","emboss_water","emboss_merger")(operation))"fasta"else"tsv"
 case class Aligned(a:String,b:String,score:Double,startA:Int,endA:Int,startB:Int,endB:Int)
 private def one(in:InputStream):(String,String)={var id:Option[String]=None;val seq=new java.lang.StringBuilder;lines(in){s=>active();if(s.startsWith(">")){require(id.isEmpty&&s.drop(1).trim.nonEmpty,"exactly one FASTA sequence required");id=Some(s.drop(1).takeWhile(!_.isWhitespace))}else if(s.nonEmpty){require(id.nonEmpty&&s.forall(c=>"ACGTacgt".contains(c)),"unambiguous DNA required");require(seq.length.toLong+s.length<=1000000,"sequence exceeds 1000000 bases");seq.append(s.toUpperCase(Locale.ROOT))}};require(id.nonEmpty&&seq.length>0,"one nonempty FASTA required");id.get->seq.toString}
 private def align(a:String,b:String,p:JsonNode,local:Boolean):Aligned={
  val width=b.length+1;val cells=(a.length.toLong+1)*width;require(cells<=1000000,"alignment DP exceeds 1000000 cells");val size=cells.toInt
  val scores=Array.fill(3)(Array.fill(size)(if(local)0d else Double.NegativeInfinity));val trace=Array.fill(3)(new Array[Byte](size));scores(0)(0)=0
  def open=param(p,"gap_open").asInt;def extend=param(p,"gap_extend").asInt
  def choose(v0:Double,v1:Double,v2:Double):(Double,Byte)=if(v0>=v1&&v0>=v2)(v0,0)else if(v1>=v2)(v1,1)else(v2,2)
  if(!local){(1 to a.length).foreach{i=>scores(1)(i*width)= -open-(i-1).toDouble*extend;trace(1)(i*width)=if(i==1)0 else 1};(1 to b.length).foreach{j=>scores(2)(j)= -open-(j-1).toDouble*extend;trace(2)(j)=if(j==1)0 else 2}}
  var best=0d;var endI=0;var endJ=0;var state:Byte=0;var i=1
  while(i<=a.length){active();var j=1;while(j<=b.length){val at=i*width+j;val diagonal=at-width-1;val up=at-width;val left=at-1
   val m=choose(scores(0)(diagonal),scores(1)(diagonal),scores(2)(diagonal));val x=choose(scores(0)(up)-open,scores(1)(up)-extend,scores(2)(up)-open);val y=choose(scores(0)(left)-open,scores(1)(left)-open,scores(2)(left)-extend)
   val all=Vector((m._1+(if(a(i-1)==b(j-1))param(p,"match_score").asInt else param(p,"mismatch_score").asInt),m._2),x,y)
   (0 until 3).foreach{s=>scores(s)(at)=if(local)math.max(0,all(s)._1)else all(s)._1;trace(s)(at)=all(s)._2;if(local&&scores(s)(at)>best){best=scores(s)(at);endI=i;endJ=j;state=s.toByte}}
   j+=1
  };i+=1}
  if(!local){endI=a.length;endJ=b.length;val at=endI*width+endJ;val chosen=choose(scores(0)(at),scores(1)(at),scores(2)(at));best=chosen._1;state=chosen._2}
  i=endI;var j=endJ;val outA=new java.lang.StringBuilder;val outB=new java.lang.StringBuilder
  while((i>0||j>0)&&(!local||scores(state)(i*width+j)>0)){
   active();val previous=trace(state)(i*width+j);state match{
    case 0=>require(i>0&&j>0,"invalid match traceback");outA.append(a(i-1));outB.append(b(j-1));i-=1;j-=1
    case 1=>require(i>0,"invalid deletion traceback");outA.append(a(i-1));outB.append('-');i-=1
    case 2=>require(j>0,"invalid insertion traceback");outA.append('-');outB.append(b(j-1));j-=1
   };state=previous
  }
  Aligned(outA.reverse.toString,outB.reverse.toString,best,if(endI==0)0 else i+1,endI,if(endJ==0)0 else j+1,endJ)
 }
 protected def runPair(reference:InputStream,source:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n");val (rightId,b)=one(reference);val(leftId,a)=one(source)
  if(Set("emboss_needle","emboss_water","emboss_diffseq")(operation)){
   val result=align(a,b,p,operation=="emboss_water")
   if(operation!="emboss_diffseq"){val score=String.format(Locale.ROOT,"%.6f",Double.box(result.score));row(s">$leftId score=$score start=${result.startA} end=${result.endA}");row(result.a);row(s">$rightId score=$score start=${result.startB} end=${result.endB}");row(result.b)}
   else{
    row("reference_offset\tquery_offset\treference\tquery\ttype");var qa=0;var rb=0;var startQ=0;var startR=0;var kind="";val query=new java.lang.StringBuilder;val ref=new java.lang.StringBuilder
    def flush():Unit=if(kind.nonEmpty){row(s"$startR\t$startQ\t${if(ref.length==0)"-"else ref.toString}\t${if(query.length==0)"-"else query.toString}\t$kind");query.setLength(0);ref.setLength(0);kind=""}
    result.a.indices.foreach{i=>active();val x=result.a(i);val y=result.b(i);val k=if(x==y)""else if(x=='-')"deletion"else if(y=='-')"insertion"else"substitution";if(k!=kind){flush();if(k.nonEmpty){kind=k;startQ=qa;startR=rb}};if(k.nonEmpty){if(x!='-')query.append(x);if(y!='-')ref.append(y)};if(x!='-')qa+=1;if(y!='-')rb+=1};flush()
   }
  }else if(operation=="emboss_merger"){
   val text=b+"#"+a;val prefix=new Array[Int](text.length);var i=1;while(i<text.length){if((i&4095)==0)active();var j=prefix(i-1);while(j>0&&text(i)!=text(j))j=prefix(j-1);if(text(i)==text(j))j+=1;prefix(i)=j;i+=1};val overlap=prefix.last;require(overlap>=param(p,"minimum_overlap").asInt,"minimum suffix-prefix overlap absent");row(s">${leftId}_merged_$rightId");row(a+b.drop(overlap))
  }else{
   var hits=0;def hit(s:String):Unit={hits+=1;require(hits<=100000,"pair output exceeds 100000 matches");row(s)}
   operation match{
    case "emboss_dottup"=>val k=param(p,"word").asInt;require(a.length-k+1<=100000&&b.length-k+1<=100000,"word index exceeds 100000 positions");val index=mutable.Map.empty[String,mutable.ArrayBuffer[Int]];var j=0;while(j+k<=b.length){active();index.getOrElseUpdate(b.substring(j,j+k),mutable.ArrayBuffer.empty)+=j;j+=1};var i=0;while(i+k<=a.length){active();val word=a.substring(i,i+k);index.getOrElse(word,mutable.ArrayBuffer.empty).foreach(j=>hit(s"$leftId\t$rightId\t${i+1}\t${j+1}\t$word"));i+=1}
    case "emboss_dotmatcher"=>val window=param(p,"window").asInt;val m=math.max(0,a.length-window+1);val n=math.max(0,b.length-window+1);require(m.toLong*n*window<=64000000,"dotmatcher comparison budget exceeded");var i=0;while(i<m){active();var j=0;while(j<n){var score=0;var k=0;while(k<window){score+= (if(a(i+k)==b(j+k))2 else -1);k+=1};if(score>=param(p,"threshold").asInt)hit(s"$leftId\t$rightId\t${i+1}\t${j+1}\t$score");j+=1};i+=1}
    case "emboss_wordmatch"=>require(a.length.toLong*b.length<=16000000,"wordmatch start budget exceeded");var extensions=0L;var i=0;while(i<a.length){active();var j=0;while(j<b.length){if(a(i)==b(j)&&(i==0||j==0||a(i-1)!=b(j-1))){var k=0;while(i+k<a.length&&j+k<b.length&&a(i+k)==b(j+k)){extensions+=1;require(extensions<=64000000,"wordmatch extension budget exceeded");if((extensions&4095)==0)active();k+=1};if(k>=param(p,"minimum_length").asInt)hit(s"$leftId\t$rightId\t${i+1}\t${j+1}\t$k\t${a.substring(i,i+k)}")};j+=1};i+=1}
    case _=>throw new IllegalArgumentException("unknown alignment operation")
   }
  }
 }
}
