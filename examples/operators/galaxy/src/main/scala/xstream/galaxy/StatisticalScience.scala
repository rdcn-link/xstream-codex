package xstream.galaxy
import java.io.InputStream
import java.util.Locale
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Small dense numerical kernels; callers impose dimensions and cancellation checks. */
object StatisticalMath {
 def solve(a0:Array[Array[Double]],b0:Array[Double]):Array[Double]={val n=b0.length;val a=a0.map(_.clone);val b=b0.clone
  (0 until n).foreach{k=>val pivot=(k until n).maxBy(i=>math.abs(a(i)(k)));require(math.abs(a(pivot)(k))>1e-12,"singular linear system");val temp=a(k);a(k)=a(pivot);a(pivot)=temp;val v=b(k);b(k)=b(pivot);b(pivot)=v
   (k+1 until n).foreach{i=>val factor=a(i)(k)/a(k)(k);(k until n).foreach{j=>a(i)(j)-=factor*a(k)(j)};b(i)-=factor*b(k)}}
  val x=new Array[Double](n);(0 until n).reverse.foreach{i=>x(i)=(b(i)-(i+1 until n).map(j=>a(i)(j)*x(j)).sum)/a(i)(i)};require(x.forall(_.isFinite),"nonfinite solution");x
 }
 def logGamma(z:Double):Double={val c=Vector(676.5203681218851,-1259.1392167224028,771.32342877765313,-176.61502916214059,12.507343278686905,-0.13857109526572012,9.9843695780195716e-6,1.5056327351493116e-7);val x=.99999999999980993+c.indices.map(i=>c(i)/(z+i)).sum;val t=z+6.5;.9189385332046727+(z-.5)*math.log(t)-t+math.log(x)}
 def betaTail(x:Double,a:Double,b:Double):Double={
  if(x<=0)0d else if(x>=1)1d else if(x>(a+1)/(a+b+2))1-betaTail(1-x,b,a)else{
   def nonzero(v:Double)=if(math.abs(v)<1e-300)1e-300 else v
   var c=1d;var d=1/nonzero(1-(a+b)*x/(a+1));var h=d;var converged=false;var m=1
   while(m<=300 && !converged){val aa=m*(b-m)*x/((a+2*m-1)*(a+2*m));d=1/nonzero(1+aa*d);c=nonzero(1+aa/c);h*=d*c;val ab= -(a+m)*(a+b+m)*x/((a+2*m)*(a+2*m+1));d=1/nonzero(1+ab*d);c=nonzero(1+ab/c);val delta=d*c;h*=delta;converged=math.abs(delta-1)<3e-14;m+=1};require(converged,"beta continued fraction failed");math.exp(logGamma(a+b)-logGamma(a)-logGamma(b)+a*math.log(x)+b*math.log1p(-x))*h/a
  }
 }
}
abstract class StatisticalScience(op:String) extends ExtendedOperator(op){
 override protected def format:String=if(operation=="scatterplot")"svg"else"tsv"
 private def columns(p:JsonNode,key:String):Vector[Int]={val a=param(p,key).elements.asScala.map(_.asInt-1).toVector;require(a.nonEmpty&&a.distinct.size==a.size,"unique nonempty column list required");a}
 override protected def validate(p:JsonNode):Unit={super.validate(p);if(Set("linear_regression","partial_r_square","logistic_regression")(operation)){val x=columns(p,"predictor_columns");require(!x.contains(param(p,"response_column").asInt-1),"response cannot be predictor")};if(operation=="pca")columns(p,"columns")}
 protected def run(in:InputStream,emit:String=>Unit,p:JsonNode):Unit={
  def row(s:String):Unit=emit(s+"\n");def n(k:String)=param(p,k).asInt;def fmt(v:Double):String={require(v.isFinite,"nonfinite result");String.format(Locale.ROOT,"%.6f",Double.box(if(math.abs(v)<.0000005)0d else v))}
  val buffer=mutable.ArrayBuffer.empty[Array[Double]];var chars=0L;var width=0
  lines(in){s=>active();if(s.nonEmpty && !s.startsWith("#")){val fields=s.split("\t",-1);if(width==0)width=fields.length;require(fields.length==width&&width<=128,"rectangular numeric TSV required");chars+=s.length+64;require(chars<=8388608&&buffer.size<10000,"table buffer limit exceeded");val a=fields.map(_.toDouble);require(a.forall(x=>x.isFinite&&math.abs(x)<=1e12),"bounded finite numeric data required");buffer+=a}}
  val rows=buffer.toVector;require(rows.nonEmpty,"numeric observations required");val count=rows.size
  def col(index:Int):Array[Double]={require(index>=0&&index<width,"selected column missing");rows.map(_(index)).toArray}
  def single=col(n("column")-1)
  def centered(x:Array[Double]):(Double,Array[Double])={val mean=x.sum/x.length;(mean,x.map(_-mean))}
  def regression(indices:Vector[Int],y:Array[Double]):(Array[Double],Double)={
   require(count>indices.size+1,"insufficient regression observations");val values=indices.map(i=>centered(col(i)));val scale=values.map(x=>math.sqrt(x._2.map(v=>v*v).sum/count));require(scale.forall(_>1e-12),"constant predictor")
   val design=Vector(Array.fill(count)(1d))++values.zip(scale).map{case((_,v),s)=>v.map(_/s)};val size=design.size;val q=Array.ofDim[Double](size,count);val r=Array.ofDim[Double](size,size)
   design.indices.foreach{j=>active();val v=design(j).clone;(0 until 2).foreach{_=>(0 until j).foreach{i=>val projection=v.indices.map(k=>q(i)(k)*v(k)).sum;r(i)(j)+=projection;v.indices.foreach{k=>v(k)-=projection*q(i)(k)}}};val norm=math.sqrt(v.map(x=>x*x).sum);require(norm>math.sqrt(count)*1e-10,"rank-deficient predictors");r(j)(j)=norm;v.indices.foreach{k=>q(j)(k)=v(k)/norm}}
   val rhs=q.map(v=>v.indices.map(i=>v(i)*y(i)).sum);val coef=StatisticalMath.solve(r,rhs);val residual=(0 until count).map{i=>active();val predicted=design.indices.map(j=>design(j)(i)*coef(j)).sum;math.pow(y(i)-predicted,2)}.sum
   val slopes=indices.indices.map(j=>coef(j+1)/scale(j)).toArray;val intercept=coef(0)-indices.indices.map(j=>slopes(j)*values(j)._1).sum;(Array(intercept)++slopes,residual)
  }
  operation match{
   case "linear_regression"|"partial_r_square"=>val indices=columns(p,"predictor_columns");val y=col(n("response_column")-1);val(coef,sse)=regression(indices,y)
    if(operation=="linear_regression"){row("term\tcoefficient");row("intercept\t"+fmt(coef(0)));indices.zipWithIndex.foreach{case(c,j)=>row(s"column_${c+1}\t${fmt(coef(j+1))}")};val total=centered(y)._2.map(x=>x*x).sum;row("r_squared\t"+fmt(if(total==0)1d else math.max(0d,1-sse/total)))}
    else{row("column\tpartial_r_squared");indices.foreach{index=>val reduced=regression(indices.filterNot(_==index),y)._2;row(s"${index+1}\t${fmt(if(reduced==0)0d else math.max(0d,math.min(1d,(reduced-sse)/reduced)))}")}}
   case "histogram"=>val x=single;val lo=if(x.min==x.max)x.min-.5 else x.min;val hi=if(x.min==x.max)x.max+.5 else x.max;val step=(hi-lo)/n("bins");val counts=Array.fill(n("bins"))(0);x.foreach{v=>active();counts(math.min(n("bins")-1,((v-lo)/step).toInt))+=1};row("lower\tupper\tcount");counts.indices.foreach{i=>row(s"${fmt(lo+i*step)}\t${fmt(if(i==counts.length-1)hi else lo+(i+1)*step)}\t${counts(i)}")}
   case "scatterplot"=>val x=col(n("x_column")-1);val y=col(n("y_column")-1);def coordinate(v:Double,lo:Double,hi:Double)=if(hi==lo).5 else(v-lo)/(hi-lo);val xmin=x.min;val xmax=x.max;val ymin=y.min;val ymax=y.max
    emit("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 640 420\"><path d=\"M40 20V380H620\" fill=\"none\" stroke=\"#64748b\"/>");x.indices.foreach{i=>active();emit(s"<circle cx=\"${fmt(40+580*coordinate(x(i),xmin,xmax))}\" cy=\"${fmt(380-360*coordinate(y(i),ymin,ymax))}\" r=\"3\" fill=\"#0891b2\"/>")};row("</svg>")
   case "statistical_hypothesis_testing"=>val x=single;require(count>=2,"two observations required");val(mean,c)=centered(x);val variance=c.map(v=>v*v).sum/(count-1);require(variance>0,"positive sample variance required");val statistic=(mean-n("null_mean_x1000")/1000d)/math.sqrt(variance/count);val df=count-1;val probability=StatisticalMath.betaTail(df/(df+statistic*statistic),df/2d,.5);row("n\tmean\tt_statistic\tdegrees_freedom\tp_value");row(s"$count\t${fmt(mean)}\t${fmt(statistic)}\t$df\t${fmt(math.max(0d,math.min(1d,probability)))}")
   case "pca"=>require(count>=2,"PCA needs two observations");val indices=columns(p,"columns");val data=indices.map(i=>centered(col(i))._2);val size=data.size;val a=Array.tabulate(size,size){(i,j)=>data(i).indices.map(k=>data(i)(k)*data(j)(k)).sum/(count-1)};val vectors=Array.tabulate(size,size)((i,j)=>if(i==j)1d else 0d);val norm=math.max(1d,a.indices.map(i=>math.abs(a(i)(i))).max);var converged=size==1;var iteration=0
    while(!converged&&iteration<100*size*size){active();val(u,v)=(for(i<-0 until size;j<-i+1 until size)yield(i,j)).maxBy{case(i,j)=>math.abs(a(i)(j))};if(math.abs(a(u)(v))<=norm*1e-13)converged=true else{val angle=.5*math.atan2(2*a(u)(v),a(v)(v)-a(u)(u));val c=math.cos(angle);val s=math.sin(angle);val uu=c*c*a(u)(u)-2*s*c*a(u)(v)+s*s*a(v)(v);val vv=s*s*a(u)(u)+2*s*c*a(u)(v)+c*c*a(v)(v)
     (0 until size).filter(k=>k!=u&&k!=v).foreach{k=>val x=a(k)(u);val y=a(k)(v);a(k)(u)=c*x-s*y;a(u)(k)=a(k)(u);a(k)(v)=s*x+c*y;a(v)(k)=a(k)(v)};a(u)(u)=uu;a(v)(v)=vv;a(u)(v)=0;a(v)(u)=0;(0 until size).foreach{k=>val x=vectors(k)(u);val y=vectors(k)(v);vectors(k)(u)=c*x-s*y;vectors(k)(v)=s*x+c*y}}
     iteration+=1};require(converged,"PCA eigensolver did not converge");val order=(0 until size).sortBy(i=>(-a(i)(i),i));val signs=order.map{j=>val k=(0 until size).maxBy(i=>(math.abs(vectors(i)(j)),-i));if(vectors(k)(j)<0)-1 else 1};row("row\t"+order.indices.map(i=>"PC"+(i+1)).mkString("\t"));(0 until count).foreach{k=>active();row((k+1)+"\t"+order.zip(signs).map{case(j,sign)=>fmt(data.indices.map(i=>data(i)(k)*vectors(i)(j)*sign).sum)}.mkString("\t"))}
   case "logistic_regression"=>val indices=columns(p,"predictor_columns");val y=col(n("response_column")-1);require(y.forall(v=>v==0||v==1)&&y.distinct.length==2&&count>indices.size+1,"binary response with both classes and enough rows required");val raw=indices.map(i=>centered(col(i)));val scales=raw.map(x=>math.sqrt(x._2.map(v=>v*v).sum/count));require(scales.forall(_>1e-12),"constant predictor");val design=Array.tabulate(count,indices.size+1){(i,j)=>if(j==0)1d else raw(j-1)._2(i)/scales(j-1)};val dim=indices.size+1;val ridge=n("ridge_x1000")/1000d;var beta=Array.fill(dim)(0d);var converged=false;var iteration=0
    def objective(b:Array[Double]):Double={var loss=0d;(0 until count).foreach{i=>if((i&255)==0)active();val z=(0 until dim).map(j=>design(i)(j)*b(j)).sum;loss+=math.max(z,0)+math.log1p(math.exp(-math.abs(z)))-y(i)*z};loss+ridge*b.drop(1).map(v=>v*v).sum/2}
    while(!converged&&iteration<100){active();val gradient=Array.fill(dim)(0d);val hessian=Array.ofDim[Double](dim,dim);(0 until count).foreach{i=>if((i&255)==0)active();val z=(0 until dim).map(j=>design(i)(j)*beta(j)).sum;val mu=if(z>=0)1/(1+math.exp(-z))else{val e=math.exp(z);e/(1+e)};val w=math.max(1e-12,mu*(1-mu));(0 until dim).foreach{j=>gradient(j)+=design(i)(j)*(mu-y(i));(0 until dim).foreach{k=>hessian(j)(k)+=design(i)(j)*design(i)(k)*w}}};(1 until dim).foreach{j=>gradient(j)+=ridge*beta(j);hessian(j)(j)+=ridge};val delta=StatisticalMath.solve(hessian,gradient);val before=objective(beta);var step=1d;var candidate=beta.indices.map(j=>beta(j)-step*delta(j)).toArray;while(objective(candidate)>before+1e-12&&step>1e-8){active();step/=2;candidate=beta.indices.map(j=>beta(j)-step*delta(j)).toArray};require(step>1e-8,"logistic line search failed");converged=delta.map(v=>math.abs(step*v)).max<1e-8;beta=candidate;iteration+=1};require(converged,"logistic regression did not converge");val slopes=indices.indices.map(j=>beta(j+1)/scales(j));val intercept=beta(0)-slopes.indices.map(j=>slopes(j)*raw(j)._1).sum;row("term\tcoefficient");row("intercept\t"+fmt(intercept));indices.zip(slopes).foreach{case(c,v)=>row(s"column_${c+1}\t${fmt(v)}")}
   case _=>throw new IllegalArgumentException("unknown statistical operation")
  }
 }
}
