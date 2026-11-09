package xstream.data.hdf5

import xstream.data._
import xstream.data.process.ReaderProcess
import java.nio.{ByteBuffer,ByteOrder}
import java.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

final class Hdf5Format extends StructuredFileHandler {
 val id="hdf5";val extensions=Set("h5","hdf5","hdf")
 private def hdfSignature(b:Array[Byte]):Boolean=b.take(8).sameElements(Array[Byte](0x89.toByte,72,68,70,13,10,26,10))
 override def matches(b:Array[Byte],extension:String):Boolean=extensions(extension) || (!Set("nc","nc4","cdf")(extension) && hdfSignature(b))
 override def validateOptions(o:FileOptions):Unit=ReaderProcess.validate(o)
 def openStructure(source:ByteSource,options:FileOptions,context:DatasetContext,check:()=>Unit):DatasetAccess={
  validateOptions(options)
  ReaderProcess.guarded{
   val worker=ReaderProcess.open(source,options,sys.env.getOrElse("XSTREAM_HDF5_PYTHON","/usr/bin/python3"),ReaderProcess.script(getClass,"/xstream/data/hdf5/reader.py"),"hdf5",check)
   try{
    val info=worker.receive(check);val objects=info.path("entries").elements.asScala.toVector
    objects.foreach(o=>LogicalPath.validate(o.path("path").asText))
    val chunk=ReaderProcess.number(options,"chunk_bytes",65536,8,DataViewSupports.MaxBlockBytes).toInt
    new StructuredDataAccess(source,context.byteChunk){
     val entries=objects.map(o=>Item(o.path("path").asText,o.path("format").asText,None,o.path("kind").asText,views=Some(if(o.path("supported").asBoolean)Seq("array")else Seq.empty)))
     def content[S](path:String,kind:DataViewType[S]):Option[DataView[S]]={
      val meta=objects.find(_.path("path").asText==path).get
      DataViews.when(kind,ScientificViews.Arrays)(DataViews.blocks[ArrayChunk,ArraySelection]((request,c,out)=>worker.synchronized{ReaderProcess.guarded{try{
       val dimensions=meta.path("shape").elements.asScala.zipWithIndex.map{case(n,i)=>Dimension(Option(meta.path("labels").path(i).asText).filter(_.nonEmpty).getOrElse("axis"+i),n.asLong)}.toVector
       val encoding=NumericEncoding(meta.path("encoding").asText,ByteOrder.LITTLE_ENDIAN)
       val selection=ArrayBlocks.selected(request,dimensions);selection.validate(dimensions)
       var attrs=meta.fields.asScala.filterNot(e=>Set("path","kind","format","supported","encoding","shape")(e.getKey)).map(e=>e.getKey->e.getValue).toMap
       val groups=Data.mapper.createObjectNode();info.path("group_attributes").fields.asScala.filter(e=>e.getKey=="/" || path.startsWith(e.getKey+"/")).foreach(e=>groups.set[JsonNode](e.getKey,e.getValue))
       attrs+=("group_attributes"->groups);attrs+=("values"->Data.mapper.getNodeFactory.textNode("raw"))
       out.start(ArrayDescriptor(dimensions,encoding,attrs))
       ArrayBlocks.sections(selection,dimensions,encoding,chunk){section=>
        check();c.check();val req=Data.mapper.createObjectNode().put("path",path)
        req.set[JsonNode]("origin",Data.mapper.valueToTree(section.origin.asJava));req.set[JsonNode]("shape",Data.mapper.valueToTree(section.shape.asJava));req.set[JsonNode]("stride",Data.mapper.valueToTree(section.stride.asJava))
        worker.send(req);val bytes=Base64.getDecoder.decode(worker.receive(()=>{check();c.check()}).path("data").asText)
        c.check();out.accept(ArrayChunk(section,encoding,ByteBuffer.wrap(bytes).order(encoding.byteOrder)))
       }
      }catch{case t:Throwable=>worker.close();throw t}}}))
     }
     override def close():Unit=worker.close()
    }
   }catch{case t:Throwable=>worker.close();throw t}
  }
 }
}
