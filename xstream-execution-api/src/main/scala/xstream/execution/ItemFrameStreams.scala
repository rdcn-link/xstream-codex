package xstream.execution

import _root_.xstream.data._
/** Typed local streams are encoded only at the operator protocol boundary. */
object ItemFrameStreams {
  def encoded(format:String,emit:OperatorFrame=>Unit):ArrowConsumer=new ArrowConsumer {
    def start(schema:org.apache.arrow.vector.types.pojo.Schema,d:org.apache.arrow.vector.dictionary.DictionaryProvider):Unit={
      val structure=ArrowStreamMetadata.descriptor(schema).deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
      val view=ArrowStreamMetadata.view(schema);structure.put("view",view).put("version",1)
      emit(StreamProtocol.start("",view,format,Some(schema),Some(structure),d))
    }
    def accept(b:ArrowBatch):Unit=emit(StreamProtocol.frame("data",data=StreamProtocol.batch(b.root,b.dictionaries)))
  }
  def remote(format:String,cancel:StreamCancel)(read:ArrowConsumer=>Unit):FrameSource={
    val queue=new FrameQueue(cancel)
    val worker=StreamProtocol.thread(cancel){read(encoded(format,queue.put));queue.put(StreamProtocol.frame("end"));queue.end()}
    new FrameSource {def next():Option[OperatorFrame]=queue.next();def close():Unit={worker.interrupt();worker.join(5000)}}
  }

  def consume(access:DatasetAccess,path:String,view:String,format:String,scan:Option[Scan],context:StreamContext,codec:Option[DataViewSupport]=None,selection:Option[com.fasterxml.jackson.databind.JsonNode]=None)(emit:OperatorFrame=>Unit):Unit={
    if(view=="records") {
      val stream=access.requireView(path,DataViews.Records).open(scan.getOrElse(Scan()),context)
      try ArrowBlocks.consume(stream,new DataConsumer[RecordBatch] {
        def start(d:DataDescriptor[RecordBatch]):Unit={val r=d.asInstanceOf[RecordsDescriptor];emit(StreamProtocol.start("",view,format,Some(r.schema),dictionaries=r.dictionaries))}
        def accept(b:RecordBatch):Unit={context.check();emit(StreamProtocol.frame("data",data=StreamProtocol.batch(b.root,b.dictionaries)))}
      }) finally stream.close()
    } else if(view!="bytes") {
      require(scan.isEmpty,"scientific views cannot use SQL")
      val c=codec.getOrElse(throw new IllegalArgumentException("view codec unavailable: "+view))
      c.send(access,path,selection.getOrElse(Data.mapper.createObjectNode()),context,encoded(format,emit))
    } else {
      require(view=="bytes" && scan.isEmpty,"unsupported input view or selection")
      val stream=access.requireView(path,DataViews.Bytes).open(AllBytes,context)
      try ArrowBlocks.consume(stream,new DataConsumer[ByteChunk] {
        def start(d:DataDescriptor[ByteChunk]):Unit=emit(StreamProtocol.start("",view,format,None))
        def accept(b:ByteChunk):Unit={var at=b.offset;val end=at+b.length;while(at<end){context.check();val n=math.min(65536,end-at);emit(StreamProtocol.frame("data",data=java.util.Arrays.copyOfRange(b.bytes,at,at+n)));at+=n}}
      }) finally stream.close()
    }
  }
}
