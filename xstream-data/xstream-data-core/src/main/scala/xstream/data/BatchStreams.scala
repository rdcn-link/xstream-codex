package xstream.data

import org.apache.arrow.memory.{ArrowBuf,BufferAllocator}
import org.apache.arrow.vector.{VectorSchemaRoot,VarBinaryVector}
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.ipc.message.IpcOption
import scala.jdk.CollectionConverters._

/** Arrow batch callback utility. No network lifecycle or transport implementation is required. */
trait BatchReceiver {
  def start(root:VectorSchemaRoot,dictionaries:DictionaryProvider,option:IpcOption):Unit
  final def start(root:VectorSchemaRoot):Unit=start(root,null,IpcOption.DEFAULT)
  def putNext():Unit
  def completed():Unit=()
  def error(t:Throwable):Unit=throw t
}
/** Helpers for implementations already producing Arrow batches. Byte envelopes stay private here. */
trait BatchDatasetAccess extends DatasetAccess {
  protected def read(path:String,view:String,allocator:BufferAllocator,out:BatchReceiver,check:()=>Unit):Unit
  protected def readScan(path:String,scan:Scan,allocator:BufferAllocator,out:BatchReceiver,check:()=>Unit):Unit =
    ScanStreams.stream(scan,allocator,out)(filtered=>read(path,"records",allocator,filtered,check))
  protected def batchViews(path:String):Option[Seq[String]]=describe(path).views
  override def view[S](path:String,kind:DataViewType[S]):Option[DataView[S]] = {
    if(!(kind eq DataViews.Records) && !(kind eq DataViews.Bytes)) return None
    if(batchViews(path).exists(v=> !v.contains(kind.name))) return None
    if(kind eq DataViews.Records) Some(DataViews[Scan]((s,c,o)=>
      if(s==Scan())read(path,"records",c.allocator,BatchStreams.arrow(o,"records",c.check),c.check)
      else readScan(path,s,c.allocator,BatchStreams.arrow(o,"records",c.check),c.check)).asInstanceOf[DataView[S]])
    else if(kind eq DataViews.Bytes) Some(DataViews[AllBytes.type]((_,c,o)=>
      read(path,"bytes",c.allocator,BatchStreams.arrow(o,"bytes",c.check),c.check)).asInstanceOf[DataView[S]])
    else None
  }
}
object BatchStreams {
  def records(out:DataConsumer[RecordBatch],check:()=>Unit):BatchReceiver = new BatchReceiver {
    private var root:VectorSchemaRoot=null
    private var dict:DictionaryProvider=null
    def start(r:VectorSchemaRoot,d:DictionaryProvider,o:IpcOption):Unit={check();root=r;dict=d;out.start(RecordsDescriptor(r.getSchema,d))}
    def putNext():Unit={check();out.accept(RecordBatch(root,dict))}
  }
  def bytes(out:DataConsumer[ByteChunk],check:()=>Unit):BatchReceiver = new BatchReceiver {
    private var root:VectorSchemaRoot=null
    def start(r:VectorSchemaRoot,d:DictionaryProvider,o:IpcOption):Unit={check();root=r;out.start(BytesDescriptor())}
    def putNext():Unit={
      check();val v=root.getVector(0).asInstanceOf[VarBinaryVector]
      (0 until root.getRowCount).foreach { i=>check();val b=v.get(i);out.accept(ByteChunk(b,0,b.length)) }
    }
  }
  /** Publishes existing Arrow buffers directly with standardized view metadata. */
  def arrow(out:ArrowConsumer,view:String,check:()=>Unit):BatchReceiver=new BatchReceiver {
    private var source:VectorSchemaRoot=null
    private var schema:org.apache.arrow.vector.types.pojo.Schema=null
    private var dictionaries:DictionaryProvider=null
    def start(r:VectorSchemaRoot,d:DictionaryProvider,o:IpcOption):Unit={
      check();source=r;dictionaries=d
      schema=ArrowRepresentations.schema(if(view=="records")RecordsDescriptor(r.getSchema,d)else BytesDescriptor())
      require(schema.getFields==r.getSchema.getFields,"view field mismatch")
      out.start(schema,d)
    }
    def putNext():Unit={check();out.accept(ArrowBatch(new VectorSchemaRoot(schema,source.getFieldVectors,source.getRowCount),dictionaries))}
  }
  def read(access:DatasetAccess,path:String,view:String,a:BufferAllocator,out:BatchReceiver,check:()=>Unit):Unit =
    if(view=="records") scan(access,path,Scan(),a,out,check)
    else {
      require(view=="bytes","unsupported stream view")
      consume(access.requireView(path,DataViews.Bytes).open(AllBytes,StreamContext(a,check)),a,out,check)
    }
  def scan(access:DatasetAccess,path:String,selection:Scan,a:BufferAllocator,out:BatchReceiver,check:()=>Unit):Unit=
    consume(access.requireView(path,DataViews.Records).open(selection,StreamContext(a,check)),a,out,check)
  /** Adapts batch callbacks by retaining Arrow buffers; no IPC encode/decode occurs here. */
  def consume(stream:ArrowDataStream,a:BufferAllocator,out:BatchReceiver,check:()=>Unit):Unit={
    var root:VectorSchemaRoot=null
    try {
      stream.consume(new ArrowConsumer {
        def start(schema:org.apache.arrow.vector.types.pojo.Schema,d:DictionaryProvider):Unit={
          check();root=VectorSchemaRoot.create(schema,a);out.start(root,d,IpcOption.DEFAULT)
        }
        def accept(b:ArrowBatch):Unit={
          check()
          val batch=new org.apache.arrow.vector.VectorUnloader(b.root).getRecordBatch
          try {
            val loader=new org.apache.arrow.vector.VectorLoader(root)
            if(b.root.getFieldVectors.asScala.forall(v=>v.getAllocator.getRoot eq a.getRoot))loader.load(batch)
            else {
              val copies=scala.collection.mutable.ArrayBuffer.empty[ArrowBuf]
              try {
                batch.getBuffers.asScala.foreach { buffer=>
                  val copy=a.buffer(buffer.readableBytes());copies+=copy
                  copy.setBytes(0L,buffer,buffer.readerIndex(),buffer.readableBytes());copy.writerIndex(buffer.readableBytes())
                }
                val local=new org.apache.arrow.vector.ipc.message.ArrowRecordBatch(batch.getLength,batch.getNodes,copies.asJava,batch.getBodyCompression,batch.getVariadicBufferCounts,true)
                try loader.load(local)finally local.close()
              } finally copies.reverseIterator.foreach(_.close())
            }
          } finally batch.close()
          out.putNext()
        }
      })
      out.completed()
    } finally {stream.close();if(root!=null)root.close()}
  }
}
