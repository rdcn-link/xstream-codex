package xstream.data.tests

import _root_.xstream.data.files.{DirectoryDatasetProvider}

import _root_.xstream.data._
import java.nio.file.{Files,Path}
import java.nio.file.attribute.{BasicFileAttributes,FileTime}
import org.scalatest.funsuite.AnyFunSuite

class ItemTimestampsSpec extends AnyFunSuite {
  private def context(environment:PluginEnvironment)=new DatasetContext {
    val batchSize=16;val inferSample=16;val byteChunk=32;val countFileRecordsOnStartup=false
    val plugins=environment;val sources=Map.empty[String,SourceDefinition]
    def createDataset(d:DatasetDefinition):PublishedDataset=throw new UnsupportedOperationException()
  }
  test("files and physical folders expose source timestamps consistently in listing and metadata") {
    val root=Files.createTempDirectory("item-times-");val folder=Files.createDirectory(root.resolve("data"))
    val file=Files.writeString(folder.resolve("weather.jsonl"),"{\"temperature\":21}\n")
    Files.setLastModifiedTime(file,FileTime.fromMillis(0))
    val plugins=new PluginEnvironment()
    val published=new DirectoryDatasetProvider().create(DatasetDefinition("directory",source=Map("root"->root.toString)),context(plugins))
    val access=published.open(()=>())
    try {
      PluginChecks.listing(access)
      val item=access.describe("/data/weather.jsonl");val attrs=Files.readAttributes(file,classOf[BasicFileAttributes])
      assert(item.creationTimeMs.contains(attrs.creationTime.toMillis));assert(item.lastModifiedTimeMs.contains(0L))
      assert(access.list("/data").head.creationTimeMs==item.creationTimeMs)
      for(path<-Seq("/","/data")){
        val f=access.describe(path);assert(f.kind=="folder")
        assert(f.creationTimeMs.isDefined && f.lastModifiedTimeMs.isDefined)
        assert(f.totalSizeBytes.isEmpty && f.recordCount.isEmpty)
      }
      Files.setLastModifiedTime(file,FileTime.fromMillis(10000))
      assert(access.describe("/data/weather.jsonl").lastModifiedTimeMs.contains(10000L))
      assert(access.describe("/data/weather.jsonl").creationTimeMs==item.creationTimeMs)
    }finally {access.close();published.close();plugins.close();Files.delete(file);Files.delete(folder);Files.delete(root)}
  }
  test("native bytes remain readable when optional record schema inference fails") {
    val root=Files.createTempDirectory("item-bytes-schema-");val file=Files.writeString(root.resolve("mixed.csv"),"temperature\n12.5\nunknown\n")
    val plugins=new PluginEnvironment();val allocator=new org.apache.arrow.memory.RootAllocator()
    val published=new DirectoryDatasetProvider().create(DatasetDefinition("directory",source=Map("root"->root.toString)),context(plugins))
    val access=published.open(()=>());val out=new java.io.ByteArrayOutputStream()
    try {
      val item=access.describe("/mixed.csv");assert(item.schema.isEmpty);assert(item.views.get.contains("bytes"))
      val wrapped=ItemByteViews.wrap(access,_.views.get,32)
      val stream=wrapped.requireView("/mixed.csv",DataViews.Bytes).open(AllBytes,StreamContext(allocator))
      try ArrowBlocks.consume(stream,new DataConsumer[ByteChunk]{
        def start(d:DataDescriptor[ByteChunk]):Unit=()
        def accept(b:ByteChunk):Unit=out.write(b.bytes,b.offset,b.length)
      })finally stream.close()
      assert(out.toByteArray.sameElements(Files.readAllBytes(file)))
      val records=access.requireView("/mixed.csv",DataViews.Records).open(Scan(),StreamContext(allocator))
      try intercept[IllegalArgumentException](records.consume(new ArrowConsumer {
        def start(s:org.apache.arrow.vector.types.pojo.Schema,d:org.apache.arrow.vector.dictionary.DictionaryProvider):Unit=()
        def accept(b:ArrowBatch):Unit=()
      }))finally records.close()
    }finally{access.close();published.close();allocator.close();plugins.close();Files.delete(file);Files.delete(root)}
  }
  test("unknown times remain absent and zero is a known timestamp") {
    val unknown=Item("/remote","bin",None)
    assert(unknown.creationTimeMs.isEmpty && unknown.lastModifiedTimeMs.isEmpty)
    val epoch=unknown.copy(creationTimeMs=Some(0L),lastModifiedTimeMs=Some(0L))
    assert(epoch.creationTimeMs.contains(0L) && epoch.lastModifiedTimeMs.contains(0L))
  }
}
