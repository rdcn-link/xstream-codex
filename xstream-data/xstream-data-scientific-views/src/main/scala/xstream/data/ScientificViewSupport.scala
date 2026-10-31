package xstream.data.scientific

import _root_.xstream.data._
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.{ByteBuffer,ByteOrder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters._

private[scientific] object Json {
  def obj():ObjectNode=Data.mapper.createObjectNode()
  def fields(n:JsonNode,allowed:Set[String]):Unit=require(n!=null && n.isObject && n.fieldNames.asScala.forall(allowed),"invalid view selection")
  def number(n:JsonNode,key:String):Long={val x=n.get(key);require(x!=null && x.isIntegralNumber && x.canConvertToLong,"expected integer: "+key);x.longValue()}
  def longs(n:JsonNode,key:String):Vector[Long]={val x=n.get(key);require(x!=null && x.isArray,"expected array: "+key);x.elements.asScala.map(v=>{require(v.isIntegralNumber && v.canConvertToLong,"expected integer array");v.longValue()}).toVector}
  def put(n:ObjectNode,key:String,values:Iterable[Long]):Unit={val a=n.putArray(key);values.foreach(a.add)}
  def attrs(n:JsonNode):Map[String,JsonNode]=Option(n.get("attributes")).map { x=>require(x.isObject,"invalid attributes");x.fields.asScala.map(e=>e.getKey->e.getValue).toMap }.getOrElse(Map.empty)
  def addAttrs(n:ObjectNode,attrs:Map[String,JsonNode]):Unit={val a=n.putObject("attributes");attrs.foreach{case(k,v)=>a.set[JsonNode](k,v)}}
  def encoding(n:JsonNode):NumericEncoding={val order=n.path("byte_order").asText;require(Set("little","big")(order),"invalid byte order");NumericEncoding(n.path("data_type").asText,if(order=="little")ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)}
  def addEncoding(n:ObjectNode,e:NumericEncoding):Unit={n.put("data_type",e.dataType);n.put("byte_order",if(e.byteOrder==ByteOrder.LITTLE_ENDIAN)"little"else"big")}
  def bytes(b:ByteBuffer):Array[Byte]={require(b.remaining<=DataViewSupports.MaxBlockBytes,"data block too large");val out=new Array[Byte](b.remaining);b.duplicate().get(out);out}
  def base(n:JsonNode,view:String):Unit=require(n.path("version").asInt==1 && n.path("view").asText==view,"unsupported view descriptor")
  def base(view:String):ObjectNode=obj().put("version",1).put("view",view)
  def section(n:JsonNode):ArraySection={fields(n,Set("origin","shape","stride"));val shape=longs(n,"shape");ArraySection(longs(n,"origin"),shape,if(n.has("stride"))longs(n,"stride")else Vector.fill(shape.size)(1L))}
  def section(s:ArraySection):ObjectNode={val n=obj();put(n,"origin",s.origin);put(n,"shape",s.shape);put(n,"stride",s.stride);n}
}
private[scientific] object Authoring {
  val integer="""{"type":"integer","minimum":0}"""
  def schema(properties:String):JsonNode=Data.mapper.readTree("""{"type":"object","properties":{REPLACE},"additionalProperties":false}""".replace("REPLACE",properties))
  def numeric(buffer:ByteBuffer,e:NumericEncoding,max:Int):JsonNode={
    val a=Data.mapper.createArrayNode();val b=buffer.duplicate().order(e.byteOrder)
    var count=0
    while(b.hasRemaining && count<max){
      e.dataType match {
        case "int8"=>a.add(b.get().toInt);case "uint8"=>a.add(b.get().toInt & 255)
        case "int16"=>a.add(b.getShort().toInt);case "uint16"=>a.add(b.getShort().toInt & 65535)
        case "int32"=>a.add(b.getInt());case "uint32"=>a.add(java.lang.Integer.toUnsignedLong(b.getInt()))
        case "int64"=>a.add(b.getLong());case "uint64"=>a.add(new java.math.BigInteger(java.lang.Long.toUnsignedString(b.getLong())))
        case "float32"=>val v=b.getFloat();if(v.isNaN||v.isInfinite)a.add(v.toString)else a.add(v)
        case "float64"=>val v=b.getDouble();if(v.isNaN||v.isInfinite)a.add(v.toString)else a.add(v)
      };count+=1
    };a
  }
}
final class ArrayViewSupport extends DataViewSupport {
  type Selection=ArraySelection
  val id="array-v1";val kind=ScientificViews.Arrays
  override def selectionSchema:JsonNode=Authoring.schema(""""origin":{"type":"array","items":{"type":"integer","minimum":0}},"shape":{"type":"array","items":{"type":"integer","minimum":0}},"stride":{"type":"array","items":{"type":"integer","minimum":1}}""")
  override def validateSelection(s:ArraySelection,schema:org.apache.arrow.vector.types.pojo.Schema):Unit={
    val d=ArrowStreamMetadata.descriptor(schema)
    val dimensions=d.path("dimensions").elements.asScala.map(n=>Dimension(n.path("name").asText,n.path("length").asLong)).toVector
    s.section.foreach(_.validate(dimensions))
  }
  override def preview(batch:ArrowBatch,maxRows:Int,maxValues:Int):Vector[JsonNode]={
    val root=batch.root
    (0 until math.min(root.getRowCount,maxRows)).map { i=>
      val n=Json.obj()
      Seq("origin","shape","stride").foreach(name=>n.set[JsonNode](name,Data.mapper.valueToTree(root.getVector(name).getObject(i))))
      val list=root.getVector("values").asInstanceOf[org.apache.arrow.vector.complex.ListVector]
      val start=list.getElementStartIndex(i);val end=list.getElementEndIndex(i)
      val values=n.putArray("values")
      (start until math.min(end,start+maxValues)).foreach { j=>
        val value=list.getDataVector.getObject(j)
        value match {
          case f:java.lang.Float if !java.lang.Float.isFinite(f)=>values.add(f.toString)
          case f:java.lang.Double if !java.lang.Double.isFinite(f)=>values.add(f.toString)
          case _=>values.add(Data.mapper.valueToTree[JsonNode](value))
        }
      }
      n.put("values_truncated",end-start>maxValues);n:JsonNode
    }.toVector
  }
  def selection(n:JsonNode):ArraySelection={Json.fields(n,Set("origin","shape","stride"));ArraySelection(if(n.size==0)None else Some(Json.section(n)))}
  def selectionJson(s:ArraySelection):JsonNode=s.section.map(Json.section).getOrElse(Json.obj())
}
final class SequenceViewSupport extends DataViewSupport {
  type Selection=SequenceSelection
  val id="sequence-v1";val kind=ScientificViews.Sequences
  override def selectionSchema:JsonNode=Authoring.schema(""""ids":{"type":"array","items":{"type":"string"}},"offset":{"type":"integer","minimum":0},"length":{"type":"integer","minimum":0}""")
  override def preview(batch:ArrowBatch,maxRows:Int,maxValues:Int):Vector[JsonNode]={
    val root=batch.root
    (0 until math.min(root.getRowCount,maxRows)).map { i=>
      def text(name:String):String=root.getVector(name).getObject(i).toString
      val symbols=text("symbols")
      val n=Json.obj().put("id",text("id")).put("offset",root.getVector("offset").asInstanceOf[org.apache.arrow.vector.BigIntVector].get(i))
        .put("last",root.getVector("last").asInstanceOf[org.apache.arrow.vector.BitVector].get(i)!=0).put("symbols",symbols.take(maxValues)).put("symbols_truncated",symbols.length>maxValues)
      if(!root.getVector("description").isNull(i))n.put("description",text("description"))
      n:JsonNode
    }.toVector
  }
  def selection(n:JsonNode):SequenceSelection={Json.fields(n,Set("ids","offset","length"));val ids=Option(n.get("ids")).map{x=>require(x.isArray && x.elements.asScala.forall(_.isTextual),"invalid sequence IDs");x.elements.asScala.map(_.asText).toSet}.getOrElse(Set.empty[String]);SequenceSelection(ids,if(n.has("offset"))Json.number(n,"offset")else 0,if(n.has("length"))Some(Json.number(n,"length"))else None)}
  def selectionJson(s:SequenceSelection):JsonNode={val n=Json.obj().put("offset",s.offset);val ids=n.putArray("ids");s.ids.toVector.sorted.foreach(ids.add);s.length.foreach(n.put("length",_));n}
}
