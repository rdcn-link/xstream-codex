package xstream.data.hdf5

import xstream.data._
import xstream.data.files.{NamespaceAdapter,PublishedItem}
import xstream.data.process.ReaderProcess
import org.scalatest.funsuite.AnyFunSuite
import org.apache.arrow.memory.RootAllocator
import java.nio.file.{Files,Path}
import java.util.concurrent.CancellationException
import scala.jdk.CollectionConverters._

class Hdf5Spec extends AnyFunSuite {
 private def using(test:(Path,DatasetContext,RootAllocator)=>Unit):Unit={
  val python=sys.env.getOrElse("XSTREAM_HDF5_PYTHON","/usr/bin/python3")
  assume(new ProcessBuilder(python,"-c","import h5py").redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()==0,"install h5py for native HDF5 contract tests")
  val home=Files.createTempDirectory("hdf5-test-");val env=new PluginEnvironment();val allocator=new RootAllocator()
  val context=new DatasetContext{val batchSize=2;val inferSample=2;val byteChunk=16;val countFileRecordsOnStartup=false;val plugins=env;val sources=Map.empty[String,SourceDefinition];def createDataset(d:DatasetDefinition):PublishedDataset=throw new UnsupportedOperationException()}
  try{assert(new ProcessBuilder(python,"-c",ReaderProcess.script(getClass,"/fixtures.py"),home.toString).inheritIO().start().waitFor()==0);test(home,context,allocator)}finally{allocator.close();env.close();val walk=Files.walk(home);try walk.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))finally walk.close()}
 }
 test("compressed big-endian arrays retain hierarchy, attributes, dimensions and strided raw values") {using{(home,c,a)=>
  val access=new Hdf5Format().openStructure(ByteSource.local(home.resolve("science.h5")),FileOptions(parameters=Map("chunk_bytes"->Int.box(8))),c,()=>())
  try{
   assert(access.list("/science").map(_.path).toSet==Set("/science/temperature","/science/cycle"))
   val values=scala.collection.mutable.ArrayBuffer.empty[Short]
   ArrowBlocks.consume(access.requireView("/science/temperature",ScientificViews.Arrays).open(ArraySelection(Some(ArraySection(Vector(1,1),Vector(2,3),Vector(2,2)))),StreamContext(a)),new DataConsumer[ArrayChunk]{
    def start(d:DataDescriptor[ArrayChunk]):Unit={val x=d.asInstanceOf[ArrayDescriptor];assert(x.dimensions.map(_.name)==Vector("y","x"));assert(x.dimensions.map(_.length)==Vector(4,6));assert(x.attributes("compression").asText=="gzip");assert(x.attributes("group_attributes").path("/science").path("unit").path("value").asText=="K");assert(x.attributes("attributes").path("scale_factor").path("value").asDouble==.1)}
    def accept(b:ArrayChunk):Unit={assert(b.values.remaining<=8);val v=b.values.duplicate().order(b.encoding.byteOrder);while(v.hasRemaining)values+=v.getShort()}
   });assert(values.toVector==Vector[Short](7,9,11,19,21,23))
  }finally access.close()
 }}
 test("scalar uint64, empty arrays, original bytes and unsupported capabilities are explicit") {using{(home,c,a)=>
  val access=new Hdf5Format().openStructure(ByteSource.local(home.resolve("science.h5")),FileOptions(),c,()=>())
  try{
   var scalar=0L
   ArrowBlocks.consume(access.requireView("/scalar",ScientificViews.Arrays).open(ArraySelection(),StreamContext(a)),new DataConsumer[ArrayChunk]{def start(d:DataDescriptor[ArrayChunk]):Unit={val x=d.asInstanceOf[ArrayDescriptor];assert(x.dimensions.isEmpty);assert(x.encoding.dataType=="uint64")};def accept(b:ArrayChunk):Unit=scalar=b.values.duplicate().order(b.encoding.byteOrder).getLong()});assert(scalar == -1L)
   var blocks=0;ArrowBlocks.consume(access.requireView("/empty",ScientificViews.Arrays).open(ArraySelection(),StreamContext(a)),new DataConsumer[ArrayChunk]{def start(d:DataDescriptor[ArrayChunk]):Unit=();def accept(b:ArrayChunk):Unit=blocks+=1});assert(blocks==0)
   for(path<-Seq("/text","/compound","/complex","/flag","/external","/soft","/virtual","/external_data")){assert(access.describe(path).views.contains(Seq.empty));assert(access.view(path,ScientificViews.Arrays).isEmpty)}
   var size=0L;ArrowBlocks.consume(access.requireView("/",DataViews.Bytes).open(AllBytes,StreamContext(a)),new DataConsumer[ByteChunk]{def start(d:DataDescriptor[ByteChunk]):Unit=();def accept(b:ByteChunk):Unit=size+=b.length});assert(size==Files.size(home.resolve("science.h5")))
  }finally access.close()
 }}
 test("registration, namespace routing and a nonlocal ByteSource use the same parser") {using{(home,c,a)=>
  assert(c.plugins.formats.byId("hdf5").isInstanceOf[Hdf5Format])
  val header=Files.readAllBytes(home.resolve("science.h5")).take(8)
  assert(new Hdf5Format().matches(header,"bin"));assert(!new Hdf5Format().matches(header,"nc4"))
  val access=new NamespaceAdapter(Seq(PublishedItem("/science.h5",file=Some(home.resolve("science.h5")))),c)
  try{assert(access.describe("/science.h5").kind=="folder");assert(access.describe("/science.h5/science/temperature").views.contains(Seq("array")))}finally access.close()
  val source=new ByteSource{val name="science.h5";val size=None;def open(check:()=>Unit)=ByteSource.local(home.resolve("science.h5")).open(check)}
  val remote=new Hdf5Format().openStructure(source,FileOptions(),c,()=>());try assert(remote.describe("/scalar").views.contains(Seq("array")))finally remote.close()
 }}
 test("corrupt data, oversized staging, bounds and cancellation are rejected") {using{(home,c,a)=>
  val f=new Hdf5Format()
  intercept[FormatFailure](f.openStructure(ByteSource.local(home.resolve("broken.h5")),FileOptions(),c,()=>()))
  intercept[FormatFailure](f.openStructure(ByteSource.local(home.resolve("science.h5")),FileOptions(parameters=Map("max_source_bytes"->Long.box(1))),c,()=>()))
  intercept[IllegalArgumentException](f.validateOptions(FileOptions(parameters=Map("external"->Boolean.box(true)))))
  val access=f.openStructure(ByteSource.local(home.resolve("science.h5")),FileOptions(),c,()=>())
  try{intercept[FormatFailure](ArrowBlocks.consume(access.requireView("/scalar",ScientificViews.Arrays).open(ArraySelection(Some(ArraySection(Vector(0),Vector(1),Vector(1)))),StreamContext(a)),new DataConsumer[ArrayChunk]{def start(d:DataDescriptor[ArrayChunk]):Unit=();def accept(b:ArrayChunk):Unit=()}))}finally access.close()
  val cancelled=f.openStructure(ByteSource.local(home.resolve("science.h5")),FileOptions(),c,()=>())
  try intercept[CancellationException]{cancelled.requireView("/scalar",ScientificViews.Arrays).open(ArraySelection(),StreamContext(a,()=>throw new CancellationException())).consume(new ArrowConsumer{def start(s:org.apache.arrow.vector.types.pojo.Schema,d:org.apache.arrow.vector.dictionary.DictionaryProvider):Unit=();def accept(b:ArrowBatch):Unit=()})}finally cancelled.close()
 }}
}
