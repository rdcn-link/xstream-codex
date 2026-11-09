package xstream.data.gdal

import xstream.data._
import xstream.data.files.{NamespaceAdapter,PublishedItem}
import xstream.data.process.ReaderProcess
import org.scalatest.funsuite.AnyFunSuite
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{VarBinaryVector,BigIntVector,VarCharVector}
import java.nio.file.{Files,Path}
import java.util.concurrent.CancellationException
import scala.jdk.CollectionConverters._

class GdalSpec extends AnyFunSuite {
 private def using(test:(Path,DatasetContext,RootAllocator)=>Unit):Unit={
  val python=sys.env.getOrElse("XSTREAM_GDAL_PYTHON","/usr/bin/python3")
  assume(new ProcessBuilder(python,"-c","from osgeo import gdal,ogr").redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()==0,"install Python GDAL to run native contract tests")
  val home=Files.createTempDirectory("gdal-test-");val env=new PluginEnvironment();val allocator=new RootAllocator()
  val context=new DatasetContext{val batchSize=2;val inferSample=2;val byteChunk=16;val countFileRecordsOnStartup=false;val plugins=env;val sources=Map.empty[String,SourceDefinition];def createDataset(d:DatasetDefinition):PublishedDataset=throw new UnsupportedOperationException()}
  val fixture=ReaderProcess.script(getClass,"/fixtures.py")
  try{assert(new ProcessBuilder(python,"-c",fixture,home.toString).inheritIO().start().waitFor()==0);test(home,context,allocator)}finally{allocator.close();env.close();val walk=Files.walk(home);try walk.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))finally walk.close()}
 }
 test("raster bands preserve georeferencing, raw strided values, masks and original bytes") {using{(home,c,a)=>
  val path=home.resolve("raster.tif");val access=new GdalRasterFormat().openStructure(ByteSource.local(path),FileOptions(parameters=Map("chunk_bytes"->Int.box(8))),c,()=>())
  try{
   assert(access.describe("/").views.contains(Seq("bytes")));assert(access.list("/bands").size==2)
   val values=scala.collection.mutable.ArrayBuffer.empty[Short]
   val stream=access.requireView("/bands/1",ScientificViews.Arrays).open(ArraySelection(Some(ArraySection(Vector(0,1),Vector(2,2),Vector(2,2)))),StreamContext(a))
   ArrowBlocks.consume(stream,new DataConsumer[ArrayChunk]{
    def start(d:DataDescriptor[ArrayChunk]):Unit={val x=d.asInstanceOf[ArrayDescriptor];assert(x.dimensions.map(_.length)==Vector(3,4));assert(x.attributes("crs_wkt").asText.contains("WGS"));assert(x.attributes("geotransform").get(0).asDouble==100);assert(x.attributes("scale").asDouble==.1)}
    def accept(b:ArrayChunk):Unit={assert(b.values.remaining<=8);val buffer=b.values.duplicate().order(b.encoding.byteOrder);while(buffer.hasRemaining)values+=buffer.getShort()}
   });assert(values.toVector==Vector[Short](101,103,109,111))
   val masks=scala.collection.mutable.ArrayBuffer.empty[Byte]
   ArrowBlocks.consume(access.requireView("/masks/1",ScientificViews.Arrays).open(ArraySelection(),StreamContext(a)),new DataConsumer[ArrayChunk]{def start(d:DataDescriptor[ArrayChunk]):Unit=();def accept(b:ArrayChunk):Unit={val x=b.values.duplicate();while(x.hasRemaining)masks+=x.get()}})
   assert(masks.head==0 && masks.tail.forall(_ == -1))
   var size=0L;ArrowBlocks.consume(access.requireView("/",DataViews.Bytes).open(AllBytes,StreamContext(a)),new DataConsumer[ByteChunk]{def start(d:DataDescriptor[ByteChunk]):Unit=();def accept(b:ByteChunk):Unit=size+=b.length});assert(size==Files.size(path))
  }finally access.close()
 }}
 test("vector drivers preserve typed attributes, WKB and CRS; limited scans can be reopened") {using{(home,c,a)=>
  for(ext<-Seq("gpkg","geojson","fgb")){
   val access=new GdalVectorFormat().openStructure(ByteSource.local(home.resolve("vector."+ext)),FileOptions(),c,()=>())
   try{
    def read(limit:Option[Long]):Vector[Long]={val values=scala.collection.mutable.ArrayBuffer.empty[Long]
     access.requireView("/layers/0",DataViews.Records).open(Scan(limit=limit),StreamContext(a)).consume(new ArrowConsumer{
      def start(s:org.apache.arrow.vector.types.pojo.Schema,d:org.apache.arrow.vector.dictionary.DictionaryProvider):Unit={
       assert(s.getCustomMetadata.get("xstream.gdal.layer_name").nonEmpty)
       val geom=s.getFields.asScala.find(_.getMetadata.get("ARROW:extension:name")=="ogc.wkb").get;assert(geom.getMetadata.get("xstream.gdal.crs_wkt").contains("WGS"))
      }
      def accept(b:ArrowBatch):Unit={val root=b.root;val geometry=root.getFieldVectors.asScala.collectFirst{case v:VarBinaryVector=>v}.get
       for(i<-0 until root.getRowCount){values+=root.getVector("value").getObject(i).asInstanceOf[Number].longValue;assert(geometry.get(i).length==21);assert(root.getVector("__fid").asInstanceOf[VarCharVector].getObject(i).toString=="original")}
      }
     });values.toVector
    }
    assert(read(Some(1)).size==1);assert(read(None).sorted==Vector(10,11,12));assert(read(Some(0)).isEmpty)
    if(ext=="gpkg"){var count=0;access.requireView("/layers/1",DataViews.Records).open(Scan(),StreamContext(a)).consume(new ArrowConsumer{def start(s:org.apache.arrow.vector.types.pojo.Schema,d:org.apache.arrow.vector.dictionary.DictionaryProvider):Unit=();def accept(b:ArrowBatch):Unit=count+=b.root.getRowCount});assert(count==0)}
   }finally access.close()
  }
 }}
 test("ServiceLoader namespace routing supports remote-like ByteSources and excludes unsupported views") {using{(home,c,a)=>
  assert(c.plugins.formats.byId("gdal-raster").isInstanceOf[GdalRasterFormat])
  val access=new NamespaceAdapter(Seq(PublishedItem("/terrain.tif",file=Some(home.resolve("raster.tif")))),c)
  try{assert(access.describe("/terrain.tif").kind=="folder");assert(access.list("/terrain.tif/bands").size==2);assert(access.describe("/terrain.tif/bands/1").views.contains(Seq("array")))}finally access.close()
  val source=new ByteSource{val name="image.png";val size=None;def open(check:()=>Unit)=ByteSource.local(home.resolve("image.png")).open(check)}
  val remote=new GdalRasterFormat().openStructure(source,FileOptions(),c,()=>())
  try{var n=0;ArrowBlocks.consume(remote.requireView("/bands/1",ScientificViews.Arrays).open(ArraySelection(),StreamContext(a)),new DataConsumer[ArrayChunk]{def start(d:DataDescriptor[ArrayChunk]):Unit=();def accept(b:ArrayChunk):Unit=n+=b.values.remaining});assert(n==4)}finally remote.close()
 }}
 test("malformed inputs, disallowed drivers, options, staging limits and cancellation fail safely") {using{(home,c,a)=>
  val handler=new GdalRasterFormat()
  for(name<-Seq("broken.tif","bad.vrt")){val e=intercept[FormatFailure](handler.openStructure(ByteSource.local(home.resolve(name)),FileOptions(),c,()=>()));assert(!e.getMessage.contains(home.toString))}
  intercept[IllegalArgumentException](handler.validateOptions(FileOptions(parameters=Map("command"->"bad"))))
  intercept[FormatFailure](handler.openStructure(ByteSource.local(home.resolve("raster.tif")),FileOptions(parameters=Map("max_source_bytes"->Long.box(1))),c,()=>()))
  val complex=handler.openStructure(ByteSource.local(home.resolve("complex.tif")),FileOptions(),c,()=>());try assert(complex.describe("/bands/1").views.contains(Seq.empty))finally complex.close()
  val unsupported=new GdalVectorFormat().openStructure(ByteSource.local(home.resolve("unsupported.geojson")),FileOptions(),c,()=>());try assert(unsupported.describe("/layers/0").views.contains(Seq.empty))finally unsupported.close()
  val access=handler.openStructure(ByteSource.local(home.resolve("raster.tif")),FileOptions(),c,()=>())
  try{intercept[CancellationException]{access.requireView("/bands/1",ScientificViews.Arrays).open(ArraySelection(),StreamContext(a,()=>throw new CancellationException())).consume(new ArrowConsumer{def start(s:org.apache.arrow.vector.types.pojo.Schema,d:org.apache.arrow.vector.dictionary.DictionaryProvider):Unit=();def accept(b:ArrowBatch):Unit=()})}}finally access.close()
 }}
 test("native process cleanup, timeout and cancellation remove staged copies"){
  val source=Files.createTempFile("native-source-",".bin");Files.writeString(source,"data")
  val script="import time; time.sleep(30)"
  val p=ReaderProcess.open(ByteSource.local(source),FileOptions(parameters=Map("worker_timeout_seconds"->Int.box(1))),"/usr/bin/python3",script,"test",()=>())
  try{intercept[FormatFailure](p.receive(()=>()));assert(Files.exists(p.home.resolve("source")))}finally p.close()
  assert(!Files.exists(p.home));assert(!p.process.isAlive);assert(Files.readString(source)=="data")
  val q=ReaderProcess.open(ByteSource.local(source),FileOptions(),"/usr/bin/python3",script,"test",()=>())
  try intercept[CancellationException](q.receive(()=>throw new CancellationException()))finally q.close()
  assert(!Files.exists(q.home));Files.delete(source)
 }
}
