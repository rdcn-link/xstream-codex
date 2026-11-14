package xstream.data.tests

import xstream.data._
import xstream.data.files.{NamespaceAdapter,PublishedItem}
import org.scalatest.funsuite.AnyFunSuite
import org.apache.arrow.memory.RootAllocator
import java.nio.file.Files
import java.util.zip.{ZipEntry,ZipOutputStream}
import scala.collection.mutable.ArrayBuffer

class ItemsSpec extends AnyFunSuite {
  private def withAccess(f:(NamespaceAdapter,RootAllocator,PluginEnvironment)=>Unit):Unit={
    val dir=Files.createTempDirectory("items-contract-")
    val zip=dir.resolve("input.zip");val out=new ZipOutputStream(Files.newOutputStream(zip))
    try Seq("labels/a.txt"->"label","labels/a.tfw"->"world").foreach {case(n,v)=>out.putNextEntry(new ZipEntry(n));out.write(v.getBytes("UTF-8"));out.closeEntry()}finally out.close()
    val csv=dir.resolve("manifest.csv");Files.writeString(csv,"uri,tag\ninput.zip/labels/a.txt,first\ninput.zip/labels/a.txt,again\ndacp://example.org:8815/science/data.csv,remote\n")
    val plugins=new PluginEnvironment();val allocator=new RootAllocator()
    val environment=plugins
    val context=new DatasetContext {
      val batchSize=2;val inferSample=8;val byteChunk=2;val countFileRecordsOnStartup=false
      val sources=Map.empty[String,SourceDefinition]
      def createDataset(d:DatasetDefinition):PublishedDataset=throw new UnsupportedOperationException()
      val plugins=environment
    }
    val access=new NamespaceAdapter(Seq(PublishedItem("/input.zip",file=Some(zip)),PublishedItem("/manifest.csv",file=Some(csv))),context)
    try f(access,allocator,plugins)finally {access.close();allocator.close();plugins.close();Data.deleteTree(dir)}
  }
  test("ZIP original bytes and items references are distinct views, recursive members retain capabilities") {withAccess {(access,a,plugins)=>
    val zip=access.describe("/input.zip")
    assert(zip.kind=="folder" && zip.views.contains(Seq("bytes","items")))
    val refs=ArrayBuffer.empty[ItemReference]
    ItemReferences.foreach(access.requireView("/input.zip",ItemReferences.View).open(ItemsSelection(recursive=true,mode="folder"),StreamContext(a)),Some("context:/science/input.zip"))(refs+=_)
    val file=refs.find(_.name=="a.txt").get
    assert(file.uri=="context:/science/input.zip/labels/a.txt" && file.views.exists(_.contains("bytes")))
    assert(refs.exists(_.kind=="folder"))
    val bytes=new java.io.ByteArrayOutputStream()
    ArrowBlocks.consume(access.requireView("/input.zip/labels/a.txt",DataViews.Bytes).open(AllBytes,StreamContext(a)),new DataConsumer[ByteChunk]{def start(d:DataDescriptor[ByteChunk]):Unit=();def accept(b:ByteChunk):Unit=bytes.write(b.bytes,b.offset,b.length)})
    assert(bytes.toString("UTF-8")=="label")
    intercept[IllegalArgumentException](ItemViews.selectForRead("records",zip,plugins))
    intercept[IllegalArgumentException](ItemViews.selectForRead("files",zip,plugins))
  }}
  test("CSV manifests preserve duplicate rows and attributes and resolve logical paths without opening remote members") {withAccess {(access,a,_)=>
    assert(access.describe("/manifest.csv").views.exists(_.contains("items")))
    val refs=ArrayBuffer.empty[ItemReference]
    ItemReferences.foreach(access.requireView("/manifest.csv",ItemReferences.View).open(ItemsSelection(mode="csv"),StreamContext(a)),Some("context:/science/manifest.csv"))(refs+=_)
    assert(refs.size==3 && refs(0).uri==refs(1).uri)
    assert(refs.head.uri=="context:/science/input.zip/labels/a.txt")
    assert(refs.head.metadata.path("tag").asText=="first" && refs.head.views.isEmpty)
    assert(refs.last.uri=="dacp://example.org:8815/science/data.csv")
  }}
  test("folder and CSV Sources validate their own selection and cancel before enumeration") {withAccess {(access,a,_)=>
    def read(path:String,s:ItemsSelection):Unit=ItemReferences.foreach(access.requireView(path,ItemReferences.View).open(s,StreamContext(a)))(_=>())
    intercept[IllegalArgumentException](read("/manifest.csv",ItemsSelection(mode="folder")))
    intercept[IllegalArgumentException](read("/input.zip",ItemsSelection(mode="csv")))
    intercept[IllegalArgumentException](read("/manifest.csv",ItemsSelection(uriColumn="missing",mode="csv")))
    intercept[java.util.concurrent.CancellationException](ItemReferences.foreach(access.requireView("/input.zip",ItemReferences.View).open(ItemsSelection(),StreamContext(a,()=>throw new java.util.concurrent.CancellationException())))(_=>()))
  }}
  test("directory provider items views enumerate live children and recurse only within the selected folder") {withAccess {(_,a,plugins)=>
    val dir=Files.createTempDirectory("directory-items-")
    Files.createDirectories(dir.resolve("selected/nested"))
    Files.writeString(dir.resolve("selected/one.txt"),"one")
    Files.writeString(dir.resolve("selected/nested/two.txt"),"two")
    Files.writeString(dir.resolve("outside.txt"),"outside")
    val settings=Data.mapper.createObjectNode()
    settings.putObject("datasets").putObject("science").put("provider","directory").putObject("source").put("root",dir.toString)
    val host=new ConfiguredDataCatalog(settings,plugins)
    val access=host.open("science")
    try {
      def refs(recursive:Boolean):Vector[ItemReference]={
        val found=ArrayBuffer.empty[ItemReference]
        ItemReferences.foreach(access.requireView("/selected",ItemReferences.View).open(ItemsSelection(recursive=recursive,mode="folder"),StreamContext(a)))(found+=_)
        found.toVector
      }
      assert(refs(false).map(_.uri).toSet==Set("/selected/one.txt","/selected/nested"))
      assert(refs(true).map(_.uri).toSet==Set("/selected/one.txt","/selected/nested","/selected/nested/two.txt"))
    }finally {access.close();host.close();Data.deleteTree(dir)}
  }}
  test("references reject credentials and traversal and preserve remote identity during rebasing") {
    for(uri<-Seq("../private","context:/ds/%2e%2e/private","dacp://user:secret@example.org/ds/a","dacp://example.org/ds/a?token=secret"))intercept[IllegalArgumentException](ItemReferences.validate(uri))
    assert(ItemReferences.resolve("/archive.zip/a.txt","dacp://example.org:8815/science/list.csv")=="dacp://example.org:8815/science/archive.zip/a.txt")
    assert(ItemReferences.resolve("context:/other/a.txt","dacp://example.org:8815/science/list.csv")=="dacp://example.org:8815/other/a.txt")
    assert(ItemReferences.resolve("context:/other/a.txt","context:/science/list.csv")=="context:/other/a.txt")
    assert(ItemReferences.resolve("dacp://other.org/ds/a","context:/science/list.csv")=="dacp://other.org/ds/a")
    intercept[IllegalArgumentException](new ItemsViewSupport().selection(Data.mapper.readTree("""{"recursive":"yes"}""")))
  }
}
