package xstream.examples.piflow

import java.io.{InputStream,ByteArrayInputStream,ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import com.fasterxml.jackson.databind.{JsonNode,ObjectMapper}
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector._
import org.apache.arrow.vector.types.pojo.{ArrowType,Field,FieldType,Schema}
import org.apache.arrow.vector.types.FloatingPointPrecision
import xstream.operator.{Operator,OperatorContext}
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}
import scala.jdk.CollectionConverters._
import scala.collection.mutable

private[piflow] trait Source {
  def schema:Schema
  def records(f:VectorSchemaRoot=>Unit):Unit
  def bytes:InputStream
}
private[piflow] trait Sink {
  def records(schema:Schema):VectorSchemaRoot=>Unit
  def bytes():java.io.OutputStream
}
private[piflow] object Support {
  val mapper=new ObjectMapper()
  val MaxBytes=128L*1024*1024
  val BatchSize=128
  def fields(s:Schema):Vector[Field]=s.getFields.asScala.toVector
  def names(s:Schema):Vector[String]=fields(s).map(_.getName)
  def unique(s:Schema):Unit=require(names(s).nonEmpty && names(s).distinct.size==names(s).size,"unique, nonempty columns required")
  def field(name:String,t:ArrowType,nullable:Boolean=true):Field=new Field(name,new FieldType(nullable,t,null),null)
  def nullable(f:Field,b:Boolean):Field=new Field(f.getName,new FieldType(b,f.getType,f.getDictionary,f.getMetadata),f.getChildren)
  def rename(f:Field,n:String):Field=new Field(n,f.getFieldType,f.getChildren)
  def schema(fs:Vector[Field],base:Schema=null):Schema=new Schema(fs.asJava,if(base==null)null else base.getCustomMetadata)
  def columns(s:String):Vector[String]={val ns=s.split(",",-1).toVector.map(_.trim);require(ns.forall(_.nonEmpty) && ns.distinct.size==ns.size,"unique, nonempty column names required");ns}
  def text(p:JsonNode,k:String,default:String=null):String={val n=p.get(k);if(n==null){require(default!=null,s"missing parameter: $k");default}else{require(n.isTextual,s"$k must be text");n.asText}}
  def bool(p:JsonNode,k:String,default:Boolean):Boolean={val n=p.get(k);if(n==null)default else {require(n.isBoolean,s"$k must be boolean");n.asBoolean}}
  def params(p:JsonNode,required:Set[String],optional:Set[String]=Set.empty):Unit={require(p.isObject,"parameters must be an object");val ks=p.fieldNames.asScala.toSet;require(required.subsetOf(ks) && ks.subsetOf(required++optional),"missing or unexpected parameters")}
  def check():Unit=if(Thread.currentThread.isInterrupted)throw new java.util.concurrent.CancellationException()
  def scalar(f:Field):Unit=require(f.getChildren.isEmpty && f.getDictionary==null && !f.getType.isInstanceOf[ArrowType.Union],"scalar, non-dictionary columns required")
  def value(v:FieldVector,i:Int):AnyRef=v.getObject(i) match {
    case null=>null
    case t:org.apache.arrow.vector.util.Text=>t.toString
    case x=>x
  }
  def set(v:FieldVector,i:Int,x:AnyRef):Unit={
    if(x==null){v.setNull(i);return}
    v match {
      case z:VarCharVector=>z.setSafe(i,x.toString.getBytes(UTF_8))
      case z:VarBinaryVector=>z.setSafe(i,x.asInstanceOf[Array[Byte]])
      case z:BigIntVector=>z.setSafe(i,x.asInstanceOf[Number].longValue)
      case z:IntVector=>z.setSafe(i,x.asInstanceOf[Number].intValue)
      case z:SmallIntVector=>z.setSafe(i,x.asInstanceOf[Number].shortValue)
      case z:TinyIntVector=>z.setSafe(i,x.asInstanceOf[Number].byteValue)
      case z:Float8Vector=>z.setSafe(i,x.asInstanceOf[Number].doubleValue)
      case z:Float4Vector=>z.setSafe(i,x.asInstanceOf[Number].floatValue)
      case z:BitVector=>z.setSafe(i,if(x==java.lang.Boolean.TRUE || x==Integer.valueOf(1))1 else 0)
      case z:UInt1Vector=>z.setSafe(i,x.asInstanceOf[Number].intValue)
      case z:UInt2Vector=>z.setSafe(i,x.asInstanceOf[Number].intValue)
      case z:UInt4Vector=>z.setSafe(i,x.asInstanceOf[Number].intValue)
      case z:UInt8Vector=>z.setSafe(i,x.asInstanceOf[Number].longValue)
      case z:DateDayVector=>z.setSafe(i,x.asInstanceOf[Number].intValue)
      case z:DateMilliVector=>z.setSafe(i,x match {case n:Number=>n.longValue;case t:java.time.LocalDateTime=>t.toInstant(java.time.ZoneOffset.UTC).toEpochMilli})
      case z:TimeSecVector=>z.setSafe(i,x.asInstanceOf[Number].intValue)
      case z:TimeMilliVector=>z.setSafe(i,x match {case n:Number=>n.intValue;case t:java.time.LocalDateTime=>(t.toLocalTime.toNanoOfDay/1000000).toInt})
      case z:TimeMicroVector=>z.setSafe(i,x.asInstanceOf[Number].longValue)
      case z:TimeNanoVector=>z.setSafe(i,x.asInstanceOf[Number].longValue)
      case z:TimeStampVector=>
        val ticks=x match {
          case n:Number=>n.longValue
          case t:java.time.LocalDateTime=>
            val instant=t.toInstant(java.time.ZoneOffset.UTC)
            z.getField.getType.asInstanceOf[ArrowType.Timestamp].getUnit match {
              case org.apache.arrow.vector.types.TimeUnit.SECOND=>instant.getEpochSecond
              case org.apache.arrow.vector.types.TimeUnit.MILLISECOND=>instant.toEpochMilli
              case org.apache.arrow.vector.types.TimeUnit.MICROSECOND=>instant.getEpochSecond*1000000+instant.getNano/1000
              case org.apache.arrow.vector.types.TimeUnit.NANOSECOND=>instant.getEpochSecond*1000000000+instant.getNano
            }
        };z.setSafe(i,ticks)
      case z:FixedSizeBinaryVector=>z.setSafe(i,x.asInstanceOf[Array[Byte]])
      case z:DecimalVector=>z.setSafe(i,new java.math.BigDecimal(x.toString))
      case z:NullVector=>require(x==null,"null field cannot contain a value")
      case _=>throw new IllegalArgumentException("sample/SQL value unsupported for "+v.getField.getType)
    }
  }
  def estimated(row:Vector[AnyRef]):Long=128L+row.map{case s:String=>s.getBytes(UTF_8).length.toLong;case b:Array[Byte]=>b.length.toLong;case _=>32L}.sum
  def rows(root:VectorSchemaRoot):Vector[Vector[AnyRef]]=(0 until root.getRowCount).map(i=>root.getFieldVectors.asScala.map(v=>value(v,i)).toVector).toVector
  def emitRows(s:Schema,rows:Iterator[Vector[AnyRef]],send:VectorSchemaRoot=>Unit):Unit={
    val allocator=new RootAllocator(MaxBytes);val root=VectorSchemaRoot.create(s,allocator)
    try rows.grouped(BatchSize).foreach { batch=>check();root.clear();root.allocateNew();batch.zipWithIndex.foreach{case(row,i)=>row.zipWithIndex.foreach{case(x,c)=>set(root.getVector(c),i,x)}};root.setRowCount(batch.size);send(root) }
    finally {root.close();allocator.close()}
  }
  def collect(in:Source):Vector[Vector[AnyRef]]={var size=0L;val result=Vector.newBuilder[Vector[AnyRef]];in.records(r=>rows(r).foreach{row=>size+=estimated(row);require(size<=MaxBytes,"materialized input exceeds 128 MiB");result+=row});result.result()}
  def projected(root:VectorSchemaRoot,target:Schema,indices:Vector[Int],send:VectorSchemaRoot=>Unit):Unit={
    val allocator=new RootAllocator(MaxBytes);val out=VectorSchemaRoot.create(target,allocator)
    try {out.allocateNew();fields(target).zipWithIndex.foreach{case(f,c)=>val from=root.getVector(f.getName);indices.zipWithIndex.foreach{case(i,j)=>if(from==null || from.getField.getType==ArrowType.Null.INSTANCE)out.getVector(c).setNull(j)else out.getVector(c).copyFromSafe(i,j,from)}};out.setRowCount(indices.size);send(out)}finally {out.close();allocator.close()}
  }
  def descriptor(schema:Option[Schema]):InferenceResult=InferenceResult(Map("result"->StreamDescriptor(Set("records"),Set("arrow"),schema,if(schema.isDefined)"known"else "unknown")))
}

/** Each entrypoint shares processing with bounded previews; no input/output data is written to disk. */
abstract class StreamOperator extends Operator {
  import Support._
  protected def ports:Set[String]=Set("source")
  protected def bytesInput:Boolean=false
  protected def validate(p:JsonNode):Unit
  protected def infer(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult
  private[piflow] def execute(inputs:Map[String,Source],out:Sink,p:JsonNode):Unit
  final def inferOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={
    require(inputs.keySet==ports,"unexpected input ports");validate(p)
    inputs.values.foreach(d=>require(d.representations.isEmpty || d.representations(if(bytesInput)"bytes"else "records"),"input representation mismatch"))
    infer(inputs,p)
  }
  final def process(ctx:OperatorContext):Unit={
    require(ctx.inputNames.toSet==ports,"unexpected input ports");validate(ctx.params)
    execute(ports.map(n=>n->new Source {
      private def in=ctx.input(n)
      def schema:Schema=in.schema
      def records(f:VectorSchemaRoot=>Unit):Unit=in.foreachRecords(f)
      def bytes:InputStream=in.byteStream()
    }).toMap,new Sink {
      def records(s:Schema):VectorSchemaRoot=>Unit={val out=ctx.recordsOutput("result",s);r=>out.write(r)}
      def bytes():java.io.OutputStream=ctx.bytesOutput("result","csv").byteStream()
    },ctx.params)
  }
  final def previewOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode,limits:PreviewLimits):Map[String,OutputSample]={
    inferOutputs(inputs,p)
    val allocator=new RootAllocator(MaxBytes)
    val roots=mutable.ArrayBuffer.empty[VectorSchemaRoot]
    var sample:OutputSample=null
    var truncated=false
    try {
      val sources=ports.map { n=>
        val d=inputs(n);val s=d.sample.getOrElse(throw new IllegalArgumentException("Upstream sample unavailable: "+n)).bounded(limits);truncated ||= s.truncated
        n->new Source {
          def schema:Schema=d.schema.getOrElse(throw new IllegalArgumentException("Upstream schema unavailable: "+n))
          def bytes:InputStream=new ByteArrayInputStream(s.data.toArray)
          def records(f:VectorSchemaRoot=>Unit):Unit={
            val root=VectorSchemaRoot.create(schema,allocator);roots+=root;root.allocateNew()
            fields(schema).zipWithIndex.foreach{case(field,c)=>s.rows.zipWithIndex.foreach{case(row,i)=>
              val v=row.get(field.getName);val x:AnyRef=if(v==null||v.isNull)null else if(v.isNumber)v.numberValue else if(v.isBoolean)java.lang.Boolean.valueOf(v.asBoolean)else if(v.isBinary)v.binaryValue else v.asText
              set(root.getVector(c),i,x)
            }};root.setRowCount(s.rows.size);f(root)
          }
        }
      }.toMap
      val collected=mutable.ArrayBuffer.empty[JsonNode];val sampleBytes=new ByteArrayOutputStream()
      execute(sources,new Sink {
        def records(s:Schema):VectorSchemaRoot=>Unit={sample=OutputSample("records","arrow");r=>{
          (0 until r.getRowCount).foreach{i=>if(collected.size>=limits.maxRows)truncated=true else {
            val row=mapper.createObjectNode();fields(s).zipWithIndex.foreach{case(f,c)=>row.set[JsonNode](f.getName,mapper.valueToTree(value(r.getVector(c),i)))}
            val proposed=OutputSample("records","arrow",(collected.toVector:+row)).bounded(limits)
            if(proposed.rows.size>collected.size)collected+=row else truncated=true
          }}
        }}
        def bytes():java.io.OutputStream={sample=OutputSample("bytes","csv");new java.io.OutputStream {
          override def write(b:Int):Unit=if(sampleBytes.size<limits.maxBytes)sampleBytes.write(b)else truncated=true
        }}
      },p)
      require(sample!=null,"operator produced no output")
      Map("result"->sample.copy(rows=collected.toVector,data=sampleBytes.toByteArray.toVector,truncated=truncated).bounded(limits))
    }finally {roots.foreach(_.close());allocator.close()}
  }
}
