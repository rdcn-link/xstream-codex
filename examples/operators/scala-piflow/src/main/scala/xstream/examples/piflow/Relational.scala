package xstream.examples.piflow

import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.vector.types.pojo.{ArrowType,Schema}
import xstream.execution.{StreamDescriptor,InferenceResult}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

private[piflow] object Relational {
  import Support._
  def merge(left:Schema,right:Schema):Schema={
    unique(left);unique(right)
    val l=fields(left).map(f=>f.getName->f).toMap;val r=fields(right).map(f=>f.getName->f).toMap
    schema((names(left)++names(right)).distinct.map { name=>
      (l.get(name),r.get(name)) match {
        case (Some(a),Some(b))=>
          require((a.getType==b.getType && a.getChildren==b.getChildren && a.getDictionary==b.getDictionary) || a.getType==ArrowType.Null.INSTANCE || b.getType==ArrowType.Null.INSTANCE,"incompatible merge field: "+name)
          val base=if(a.getType==ArrowType.Null.INSTANCE)b else a
          nullable(base,a.isNullable||b.isNullable||a.getType==ArrowType.Null.INSTANCE||b.getType==ArrowType.Null.INSTANCE)
        case (a,b)=>nullable(a.orElse(b).get,true)
      }
    },left)
  }
  def keys(p:JsonNode):Vector[String]=columns(text(p,"correlationColumn"))
  def mode(p:JsonNode):String={val m=text(p,"joinMode");require(Set("inner","left","right","full_outer")(m),"joinMode must be inner, left, right or full_outer");m}
  def join(left:Schema,right:Schema,p:JsonNode):Schema={
    unique(left);unique(right);val ks=keys(p);val m=mode(p)
    ks.foreach {k=>val a=left.findField(k);val b=right.findField(k);require(a!=null&&b!=null,"unknown join key: "+k);require(a.getType==b.getType,"join key types differ: "+k)}
    (fields(left)++fields(right)).foreach(scalar)
    val fs=fields(left).map(f=>nullable(f,f.isNullable || m=="right" || m=="full_outer"))++fields(right).filterNot(f=>ks.contains(f.getName)).map{f=>nullable(rename(f,if(names(left).contains(f.getName))f.getName+"_right"else f.getName),f.isNullable||m=="left"||m=="full_outer")}
    require(fs.map(_.getName).distinct.size==fs.size,"join produces ambiguous columns; rename input columns first")
    schema(fs,left)
  }
}
class MergeOperator extends StreamOperator {
  import Support._
  override protected def ports=Set("left","right")
  protected def validate(p:JsonNode):Unit=params(p,Set.empty)
  protected def infer(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult=descriptor(for(l<-inputs("left").schema;r<-inputs("right").schema)yield Relational.merge(l,r))
  private[piflow] def execute(inputs:Map[String,Source],out:Sink,p:JsonNode):Unit={
    val s=Relational.merge(inputs("left").schema,inputs("right").schema)
    require(fields(s).forall(_.getDictionary==null),"merge requires decoded dictionary columns")
    val send=out.records(s)
    Vector("left","right").foreach(n=>inputs(n).records(root=>projected(root,s,(0 until root.getRowCount).toVector,send)))
  }
}
object Merge extends MergeOperator
class JoinOperator extends StreamOperator {
  import Support._
  override protected def ports=Set("left","right")
  protected def validate(p:JsonNode):Unit={params(p,Set("joinMode","correlationColumn"));Relational.keys(p);Relational.mode(p)}
  protected def infer(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult=descriptor(for(l<-inputs("left").schema;r<-inputs("right").schema)yield Relational.join(l,r,p))
  private[piflow] def execute(inputs:Map[String,Source],out:Sink,p:JsonNode):Unit={
    val l=inputs("left").schema;val r=inputs("right").schema;val s=Relational.join(l,r,p)
    val ks=Relational.keys(p);val m=Relational.mode(p);val li=ks.map(names(l).indexOf);val ri=ks.map(names(r).indexOf)
    val left=collect(inputs("left"));val right=collect(inputs("right"))
    val extra=names(r).indices.filterNot(i=>ks.contains(names(r)(i))).toVector
    def key(row:Vector[AnyRef],is:Vector[Int]):Vector[AnyRef]=is.map(i=>row(i) match {case b:Array[Byte]=>b.toVector;case x=>x})
    val hash=mutable.HashMap.empty[Vector[AnyRef],mutable.ArrayBuffer[Int]]
    right.indices.foreach {i=>check();val k=key(right(i),ri);if(!k.contains(null))hash.getOrElseUpdate(k,mutable.ArrayBuffer.empty)+=i}
    val matched=mutable.BitSet.empty
    val output=left.iterator.flatMap {row=>check();val k=key(row,li);val matches=if(k.contains(null))Vector.empty else hash.get(k).map(_.toVector).getOrElse(Vector.empty)
      if(matches.nonEmpty)matches.iterator.map{i=>matched+=i;row++extra.map(right(i))}
      else if(m=="left"||m=="full_outer")Iterator(row++extra.map(_=>null))else Iterator.empty
    }++right.indices.iterator.filterNot(matched).filter(_=>m=="right"||m=="full_outer").map {i=>check();val row=right(i);val prefix=names(l).map(n=>if(ks.contains(n))row(names(r).indexOf(n))else null);prefix++extra.map(row)}
    var size=0L
    emitRows(s,output.map{row=>size+=estimated(row);require(size<=MaxBytes,"join output exceeds 128 MiB");row},out.records(s))
  }
}
object Join extends JoinOperator
