import xstream.data._
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.types.pojo.Schema
import java.io.OutputStream

/** Run against the configured weather Dataset; every method opens a fresh stream. */
object ViewExamples {
  def inspect[S](access:DatasetAccess,path:String,kind:DataViewType[S],selection:S,context:StreamContext):Unit={
    val stream=access.requireView(path,kind).open(selection,context)
    try stream.consume(new ArrowConsumer {
      def start(schema:Schema,dictionaries:DictionaryProvider):Unit=println(schema.toJson)
      def accept(batch:ArrowBatch):Unit=println(s"batch rows=${batch.root.getRowCount}")
    }) finally stream.close()
  }
  def bytes(access:DatasetAccess,path:String,context:StreamContext,out:OutputStream):Unit={
    val stream=access.requireView(path,DataViews.Bytes).open(AllBytes,context)
    try ArrowBlocks.consume(stream,new DataConsumer[ByteChunk] {
      def start(descriptor:DataDescriptor[ByteChunk]):Unit=()
      def accept(chunk:ByteChunk):Unit=out.write(chunk.bytes,chunk.offset,chunk.length)
    }) finally stream.close()
  }
  def records(access:DatasetAccess,path:String,context:StreamContext):Unit={
    val stream=access.requireView(path,DataViews.Records).open(Scan(limit=Some(3L)),context)
    try stream.consume(new ArrowConsumer {
      def start(schema:Schema,dictionaries:DictionaryProvider):Unit=println(schema.toJson)
      def accept(batch:ArrowBatch):Unit={
        import scala.jdk.CollectionConverters._
        val root=batch.root
        for(row<-0 until root.getRowCount)
          println(root.getFieldVectors.asScala.map(v=>s"${v.getName}=${v.getObject(row)}").mkString(", "))
      }
    }) finally stream.close()
  }
  def arrays(access:DatasetAccess,path:String,context:StreamContext):Unit={
    val stream=access.requireView(path,ScientificViews.Arrays).open(ArraySelection(),context)
    try ArrowBlocks.consume(stream,new DataConsumer[ArrayChunk] {
      def start(descriptor:DataDescriptor[ArrayChunk]):Unit=println(descriptor)
      def accept(chunk:ArrayChunk):Unit={
        println(s"origin=${chunk.section.origin}, shape=${chunk.section.shape}, stride=${chunk.section.stride}")
        println(s"encoding=${chunk.encoding.dataType}, payload bytes=${chunk.values.remaining()}")
      }
    }) finally stream.close()
  }
  def sequences(access:DatasetAccess,path:String,context:StreamContext):Unit={
    val stream=access.requireView(path,ScientificViews.Sequences).open(SequenceSelection(),context)
    try ArrowBlocks.consume(stream,new DataConsumer[SequenceChunk] {
      def start(descriptor:DataDescriptor[SequenceChunk]):Unit=println(descriptor)
      def accept(chunk:SequenceChunk):Unit=println(s"${chunk.id} offset=${chunk.offset} last=${chunk.last}: ${chunk.symbols}")
    }) finally stream.close()
  }
  /** output can be a socket or response body; this function creates no files. */
  def send(stream:ArrowDataStream,output:OutputStream,context:StreamContext):Unit=
    try ArrowIpc.writeStream(stream,output,context) finally stream.close()

  def main(args:Array[String]):Unit={
    val environment=new PluginEnvironment()
    val allocator=new RootAllocator()
    val dataRoot=if(args.nonEmpty) java.nio.file.Paths.get(args(0)) else Iterator.iterate(java.nio.file.Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_!=null).map(_.resolve("examples/data")).find(java.nio.file.Files.isDirectory(_)).getOrElse(throw new IllegalArgumentException("Pass the examples/data directory as the first argument"))
    val config=Data.mapper.readTree("""{"datasets":{"weather":{"provider":"directory","source":{"root":"examples/data"}}}}""")
    config.get("datasets").get("weather").get("source").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("root",dataRoot.toString)
    val host=new ConfiguredDataCatalog(config,environment)
    val access=host.open("weather")
    try {
      val context=StreamContext(allocator)
      bytes(access,"/weather-observations.jsonl",context,OutputStream.nullOutputStream())
      inspect(access,"/weather-observations.jsonl",DataViews.Records,Scan(limit=Some(1L)),context)
      records(access,"/weather-observations.jsonl",context)
      arrays(access,"/scientific/climate.nc/temperature",context)
      arrays(access,"/scientific/sky.fits/hdu0",context)
      sequences(access,"/scientific/genome.fa/sequences",context)
    }finally {access.close();host.close();allocator.close();environment.close()}
  }
}
