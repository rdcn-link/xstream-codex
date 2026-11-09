package xstream.data.gdal
import xstream.data._
import xstream.data.process.ReaderProcess
import java.nio.{ByteBuffer,ByteOrder}
import java.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.vector._
import org.apache.arrow.vector.types.pojo.{Schema,Field,FieldType,ArrowType}
import org.apache.arrow.vector.types.FloatingPointPrecision
import scala.jdk.CollectionConverters._

final class GdalRasterFormat extends GdalFormat(true) {
 val id="gdal-raster";val extensions=Set("tif","tiff","png","jpg","jpeg","bmp","gif","asc","dem")
 override def signatureMatches(b:Array[Byte]):Boolean=b.length>=4 && ((b(0)=='I' && b(1)=='I' && (b(2)==42 || b(2)==43) && b(3)==0) || (b(0)=='M' && b(1)=='M' && b(2)==0 && (b(3)==42 || b(3)==43)))
}
final class GdalVectorFormat extends GdalFormat(false) {val id="gdal-vector";val extensions=Set("geojson","gpkg","fgb")}
abstract class GdalFormat(raster:Boolean) extends StructuredFileHandler {
 override def validateOptions(o:FileOptions):Unit=ReaderProcess.validate(o)
 def openStructure(source:ByteSource,options:FileOptions,context:DatasetContext,check:()=>Unit):DatasetAccess={
  validateOptions(options)
  ReaderProcess.guarded {
   val worker=ReaderProcess.open(source,options,sys.env.getOrElse("XSTREAM_GDAL_PYTHON","/usr/bin/python3"),ReaderProcess.script(getClass,"/xstream/data/gdal/reader.py"),if(raster)"raster"else"vector",check)
   try {
    val info=worker.receive(check);val bands=info.path("bands").elements.asScala.toVector;val layers=info.path("layers").elements.asScala.toVector
    val chunk=ReaderProcess.number(options,"chunk_bytes",65536,8,DataViewSupports.MaxBlockBytes).toInt
    val entries0=if(raster)Vector(Item("/bands","gdal-bands",None,"folder",views=Some(Seq.empty)),Item("/masks","gdal-masks",None,"folder",views=Some(Seq.empty)))++bands.flatMap{b=>
     val i=b.path("index").asInt;Vector(Item(s"/bands/$i","gdal-band",None,views=Some(if(b.path("encoding").isTextual)Seq("array")else Seq.empty)),Item(s"/masks/$i","gdal-mask",None,views=Some(Seq("array"))))
    }else Vector(Item("/layers","gdal-layers",None,"folder",views=Some(Seq.empty)))++layers.map(l=>Item("/layers/"+l.path("index").asInt,"gdal-layer",None,views=Some(if(l.path("supported").asBoolean)Seq("records")else Seq.empty)))
    new StructuredDataAccess(source,context.byteChunk) {
     val entries=entries0
     private def guarded[A](f: => A):A=ReaderProcess.guarded{try f catch{case t:Throwable=>worker.close();throw t}}
     def content[S](path:String,kind:DataViewType[S]):Option[DataView[S]]=if(raster){
      val mask=path.startsWith("/masks/");val band=bands.find(_.path("index").asText==path.split('/').last).get
      DataViews.when(kind,ScientificViews.Arrays)(DataViews.blocks[ArrayChunk,ArraySelection]((request,c,out)=>worker.synchronized{guarded{
       val dimensions=Vector(Dimension("y",info.path("height").asLong),Dimension("x",info.path("width").asLong))
       val encoding=NumericEncoding(if(mask)"uint8"else band.path("encoding").asText,ByteOrder.LITTLE_ENDIAN)
       val selection=ArrayBlocks.selected(request,dimensions);selection.validate(dimensions)
       var attrs=Map("driver"->info.path("driver"),"crs_wkt"->info.path("crs_wkt"),"geotransform"->info.path("geotransform"),"pixel_coordinates"->Data.mapper.getNodeFactory.textNode("x=column,y=row; geotransform maps pixel corners; center=(x+0.5,y+0.5)"),"values"->Data.mapper.getNodeFactory.textNode("raw"))
       band.fields.asScala.filterNot(e=>Set("encoding","index")(e.getKey)).foreach(e=>attrs+=e.getKey->e.getValue)
       attrs+=("mask_item"->Data.mapper.getNodeFactory.textNode("/masks/"+band.path("index").asInt))
       out.start(ArrayDescriptor(dimensions,encoding,attrs))
       ArrayBlocks.sections(selection,dimensions,encoding,chunk){section=>
        check();c.check();val req=Data.mapper.createObjectNode().put("op","array").put("band",band.path("index").asInt).put("mask",mask)
        req.set[JsonNode]("origin",Data.mapper.valueToTree(section.origin.asJava));req.set[JsonNode]("shape",Data.mapper.valueToTree(section.shape.asJava));req.set[JsonNode]("stride",Data.mapper.valueToTree(section.stride.asJava))
        worker.send(req);val data=Base64.getDecoder.decode(worker.receive(()=>{check();c.check()}).path("data").asText)
        c.check();out.accept(ArrayChunk(section,encoding,ByteBuffer.wrap(data).order(encoding.byteOrder)))
       }
      }}))
     }else{
      val layer=layers.find(_.path("index").asText==path.split('/').last).get
      DataViews.when(kind,DataViews.Records)(DataViews.blocks[RecordBatch,Scan]((scan,c,out)=>worker.synchronized{guarded{
       val schema=GdalSchemas.layer(layer,info.path("driver").asText)
       ScanStreams.stream(scan,c.allocator,BatchStreams.records(out,c.check)){filtered=>
        val root=VectorSchemaRoot.create(schema,c.allocator)
        try{
         filtered.start(root);val req=Data.mapper.createObjectNode().put("op","records").put("layer",layer.path("index").asInt)
         check();c.check();worker.send(req);worker.receive(()=>{check();c.check()});var ended=false;var row=0;var bytes=0;root.allocateNew()
         while(!ended){
          check();c.check();worker.send(Data.mapper.createObjectNode().put("op","next").put("rows",context.batchSize).put("bytes",chunk));val msg=worker.receive(()=>{check();c.check()})
          ended=msg.path("end").asBoolean
          msg.path("rows").elements.asScala.foreach{values=>
           GdalSchemas.fill(root,row,values);row+=1;bytes+=Data.mapper.writeValueAsBytes(values).length
           if(row>=context.batchSize || bytes>=chunk){root.setRowCount(row);filtered.putNext();root.clear();root.allocateNew();row=0;bytes=0}
          }
         }
         if(row>0){root.setRowCount(row);filtered.putNext()}
        }finally root.close()
       }
      }}))
     }
     override def close():Unit=worker.close()
    }
   }catch{case t:Throwable=>worker.close();throw t}
  }
 }
}
private[gdal] object GdalSchemas {
 def layer(layer:JsonNode,driver:String):Schema={
  val fields=Vector(new Field(layer.path("fid").asText,FieldType.nullable(new ArrowType.Int(64,true)),null))++layer.path("fields").elements.asScala.map{f=>
   val t:ArrowType=if(f.path("boolean").asBoolean)new ArrowType.Bool()else f.path("encoding").asText match{
    case "int32"=>new ArrowType.Int(32,true)
    case "int64"=>new ArrowType.Int(64,true)
    case "float64"=>new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
    case "utf8"=>new ArrowType.Utf8()
   }
   new Field(f.path("name").asText,new FieldType(true,t,null,Map("xstream.gdal.native_type"->f.path("native_type").asText).asJava),null)
  }.toVector++layer.path("geometries").elements.asScala.map{g=>new Field(g.path("name").asText,new FieldType(true,new ArrowType.Binary(),null,Map("ARROW:extension:name"->"ogc.wkb","xstream.gdal.crs_wkt"->g.path("crs_wkt").asText,"xstream.gdal.geometry_type"->g.path("geometry_type").asText,"xstream.gdal.source_name"->g.path("source_name").asText).asJava),null)}.toVector
  new Schema(fields.asJava,Map("xstream.gdal.driver"->driver,"xstream.gdal.layer_name"->layer.path("name").asText,"xstream.gdal.fid_field"->layer.path("fid").asText,"xstream.gdal.geometry_fields"->layer.path("geometries").elements.asScala.map(_.path("name").asText).mkString(",")).asJava)
 }
 def fill(root:VectorSchemaRoot,row:Int,values:JsonNode):Unit={
  require(values.isArray && values.size==root.getFieldVectors.size,"GDAL row/schema mismatch")
  root.getFieldVectors.asScala.zipWithIndex.foreach{case(v,i)=>val x=values.get(i);if(x.isNull)v.setNull(row)else v match{
   case v:IntVector=>v.setSafe(row,x.intValue)
   case v:BigIntVector=>v.setSafe(row,x.longValue)
   case v:Float8Vector=>v.setSafe(row,x.asText.toDouble)
   case v:BitVector=>v.setSafe(row,if(x.asBoolean)1 else 0)
   case v:VarCharVector=>v.setSafe(row,x.asText.getBytes(java.nio.charset.StandardCharsets.UTF_8))
   case v:VarBinaryVector=>v.setSafe(row,Base64.getDecoder.decode(x.asText))
  }}
 }
}
