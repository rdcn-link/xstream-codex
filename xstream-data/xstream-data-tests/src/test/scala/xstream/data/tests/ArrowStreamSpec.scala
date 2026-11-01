package xstream.data.tests

import xstream.data._
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardOpenOption}
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector._
import org.apache.arrow.vector.dictionary.{Dictionary, DictionaryProvider}
import org.apache.arrow.vector.types.pojo.{ArrowType, DictionaryEncoding, Field, FieldType, Schema}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters._

class ArrowStreamSpec extends AnyFunSuite {
  private def drain(stream:ArrowDataStream)(onStart:(Schema,DictionaryProvider)=>Unit=(_,_)=>())(onBatch:ArrowBatch=>Unit):Unit=
    stream.consume(new ArrowConsumer {
      def start(s:Schema,d:DictionaryProvider):Unit=onStart(s,d)
      def accept(b:ArrowBatch):Unit=onBatch(b)
    })
  test("bytes and empty streams use the same IPC schema and bounded Binary rows") {
    val a=new RootAllocator()
    try for(empty<-Seq(false,true)) {
      val v=DataViews.blocks[ByteChunk,AllBytes.type]((_,_,out)=>{
        out.start(BytesDescriptor("text/plain"))
        if(!empty)Seq("abc","def").foreach(s=>{val b=s.getBytes("UTF-8");out.accept(ByteChunk(b,0,b.length))})
      })
      val output=new ByteArrayOutputStream()
      ArrowIpc.writeStream(v.open(AllBytes,StreamContext(a)),output,StreamContext(a))
      var starts=0;val result=new StringBuilder
      ArrowBlocks.consume(ArrowIpc.readStream(new ByteArrayInputStream(output.toByteArray),StreamContext(a)),new DataConsumer[ByteChunk]{
        def start(d:DataDescriptor[ByteChunk]):Unit={starts+=1;assert(d==BytesDescriptor("text/plain"))}
        def accept(b:ByteChunk):Unit=result.append(new String(b.bytes,b.offset,b.length,"UTF-8"))
      })
      assert(starts==1 && result.toString==(if(empty)""else"abcdef"))
    } finally {assert(a.getAllocatedMemory==0);a.close()}
  }
  test("array IPC File preserves dimensions, stride, unsigned values and attributes") {
    val a=new RootAllocator();val file=Files.createTempFile("array-stream-",".arrow")
    val attrs=Map("scale_factor"->Data.mapper.getNodeFactory.numberNode(0.5))
    val descriptor=ArrayDescriptor(Vector(Dimension("x",5,Some("/x"),Some("m"))),NumericEncoding("uint16",ByteOrder.BIG_ENDIAN),attrs)
    val view=DataViews.blocks[ArrayChunk,ArraySelection]((_,_,out)=>{
      out.start(descriptor)
      out.accept(ArrayChunk(ArraySection(Vector(1),Vector(2),Vector(2)),descriptor.encoding,ByteBuffer.wrap(Array[Byte](0,1,-1,-1))))
    })
    try {
      val channel=FileChannel.open(file,StandardOpenOption.WRITE)
      try {ArrowIpc.writeFile(view.open(ArraySelection(),StreamContext(a)),channel,StreamContext(a));assert(channel.isOpen)}finally channel.close()
      var rows=0
      ArrowBlocks.consume(ArrowIpc.readFile(FileChannel.open(file),StreamContext(a)),new DataConsumer[ArrayChunk]{
        def start(d:DataDescriptor[ArrayChunk]):Unit=assert(d==descriptor)
        def accept(b:ArrayChunk):Unit={rows+=1;assert(b.section==ArraySection(Vector(1),Vector(2),Vector(2)));val values=b.values.duplicate().order(b.encoding.byteOrder);assert(values.getShort()==1 && (values.getShort().toInt&65535)==65535)}
      })
      assert(rows==1)
    }finally {Files.delete(file);assert(a.getAllocatedMemory==0);a.close()}
  }
  test("a snapshot survives producer refill and closure without serializing") {
    val a=new RootAllocator();val root=VectorSchemaRoot.create(ArrowRepresentations.schema(BytesDescriptor()),a)
    var copy:OwnedArrowBatch=null
    try {
      root.allocateNew();val data=root.getVector(0).asInstanceOf[VarBinaryVector]
      data.setSafe(0,Array[Byte](1,2));root.setRowCount(1)
      copy=ArrowBatch(root).copyTo(a)
      data.setSafe(0,Array[Byte](9,9));root.close()
      assert(copy.root.getVector(0).asInstanceOf[VarBinaryVector].get(0).toSeq==Seq[Byte](1,2))
    } finally {if(copy!=null){copy.close();copy.close()};assert(a.getAllocatedMemory==0);a.close()}
  }
  test("dictionary batches and empty dictionary streams roundtrip through Arrow IPC") {
    val a=new RootAllocator()
    try for(empty<-Seq(false,true)) {
      val encoding=new DictionaryEncoding(7L,false,new ArrowType.Int(32,true))
      val field=new Field("station",new FieldType(true,new ArrowType.Int(32,true),encoding),null)
      val schema=new Schema(List(field).asJava,Map("domain"->"climate").asJava)
      val v=DataViews.blocks[RecordBatch,Scan]((_,c,out)=>{
        val dict=new VarCharVector("station-values",c.allocator)
        val root=VectorSchemaRoot.create(schema,c.allocator)
        val provider=new DictionaryProvider.MapDictionaryProvider(new Dictionary(dict,encoding))
        try {
          dict.allocateNew();dict.setSafe(0,"A".getBytes("UTF-8"));dict.setValueCount(1)
          out.start(RecordsDescriptor(schema,provider))
          if(!empty){root.allocateNew();root.getVector(0).asInstanceOf[IntVector].setSafe(0,0);root.setRowCount(1);out.accept(RecordBatch(root,provider))}
        }finally {root.close();provider.close()}
      })
      val output=new ByteArrayOutputStream();ArrowIpc.writeStream(v.open(Scan(),StreamContext(a)),output,StreamContext(a))
      var rows=0
      drain(ArrowIpc.readStream(new ByteArrayInputStream(output.toByteArray),StreamContext(a)))((s,d)=>{
        assert(s.getCustomMetadata.get("domain")=="climate");assert(d.lookup(7L).getVector.getObject(0).toString=="A")
      })(b=>{rows+=b.root.getRowCount;assert(b.dictionaries.lookup(7L).getVector.getObject(0).toString=="A")})
      assert(rows==(if(empty)0 else 1))
    }finally {assert(a.getAllocatedMemory==0);a.close()}
  }
  test("IPC writing supports unrelated allocator roots and changing dictionary providers") {
    val source=new RootAllocator();val target=new RootAllocator()
    val encoding=new DictionaryEncoding(7L,false,new ArrowType.Int(32,true))
    val field=new Field("station",new FieldType(true,new ArrowType.Int(32,true),encoding),null)
    val schema=ArrowStreamMetadata.schema(new Schema(List(field).asJava),"records",Data.mapper.createObjectNode())
    val root=VectorSchemaRoot.create(schema,source)
    def dictionary(value:String):DictionaryProvider.MapDictionaryProvider={
      val v=new VarCharVector("values",source);v.allocateNew();v.setSafe(0,value.getBytes("UTF-8"));v.setValueCount(1)
      new DictionaryProvider.MapDictionaryProvider(new Dictionary(v,encoding))
    }
    val first=dictionary("A");val second=dictionary("B")
    try {
      val header=ArrowIpc.header(schema,first,source)
      val reader=new org.apache.arrow.vector.ipc.ArrowStreamReader(new ByteArrayInputStream(header),target)
      try {assert(!reader.loadNextBatch());assert(reader.lookup(7L).getVector.getObject(0).toString=="A")}finally reader.close()
      root.allocateNew();root.getVector(0).asInstanceOf[IntVector].setSafe(0,0);root.setRowCount(1)
      val stream=ArrowDataStream(out=>{out.start(schema,first);out.accept(ArrowBatch(root,first));out.accept(ArrowBatch(root,second))})
      val output=new ByteArrayOutputStream()
      ArrowIpc.writeStream(stream,output,StreamContext(target))
      val values=scala.collection.mutable.ArrayBuffer.empty[String]
      drain(ArrowIpc.readStream(new ByteArrayInputStream(output.toByteArray),StreamContext(target)))()(b=>values+=b.dictionaries.lookup(7L).getVector.getObject(0).toString)
      assert(values.toVector==Vector("A","B"))
      assert(ArrowStreamMetadata.logical(schema)==new Schema(List(field).asJava))
    } finally {root.close();first.close();second.close();assert(source.getAllocatedMemory==0);assert(target.getAllocatedMemory==0);source.close();target.close()}
  }
  test("schema changes fail before the second batch and close parser resources") {
    val a=new RootAllocator();var released=false
    val first=ArrowRepresentations.schema(BytesDescriptor())
    val second=ArrowRepresentations.schema(BytesDescriptor("text/plain"))
    val stream=ArrowDataStream(out=>{
      val root=VectorSchemaRoot.create(second,a)
      try {out.start(first,null);out.accept(ArrowBatch(root))}finally {root.close();released=true}
    })
    try {intercept[IllegalArgumentException](drain(stream)()(b=>fail("schema mismatch must not reach consumer")));assert(released)}finally a.close()
  }
}
