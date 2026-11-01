# DataView and view-stream examples

[中文](data-view-examples_zh.md)

These examples use the repository’s JSONL, NetCDF, FITS and FASTA files. CSV and JSONL share the records interface; JPEG uses bytes, without an image view. The complete compilable program is [ViewExamples.scala](../examples/xstream/ViewExamples.scala).

## Initialize a local catalog

Run from the repository root. The `directory`, `static`, basic-format, NetCDF, FITS, FASTA and scientific-view plugins must be on the runtime classpath; the standard Runner/Designer distributions include them. Depending only on `xstream-data-core` does not install format parsers.

```scala
import xstream.data._
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.types.pojo.Schema
import java.io.OutputStream

val plugins = new PluginEnvironment()
val allocator = new RootAllocator()
val host = new ConfiguredDataCatalog(Data.mapper.readTree("""
{"datasets":{"weather":{"provider":"directory","source":{"root":"examples/data"}}}}
"""), plugins)
val access = host.open("weather")
val context = StreamContext(allocator)
// Call the examples below inside try, then close in finally:
// access.close(); host.close(); allocator.close(); plugins.close()

```
Use `access.list("/")` for direct child Items and `access.list("/scientific")` to expand a folder. `access.describe(path)` returns metadata, creation and last-modified times; `plugins.views(item)` lists supported views. Unknown timestamps are `None`. Metadata reads do not consume the content stream.

## bytes: original JSONL, CSV or JPEG bytes

Each `ByteChunk` contains the current block, not the complete file. Respect its `offset` and `length`. UTF-8 characters can cross chunk boundaries, so do not decode each chunk independently. This method writes to a caller-owned output stream and leaves it open.

```scala
def bytes(access:DatasetAccess,path:String,context:StreamContext,out:OutputStream):Unit={
    val stream=access.requireView(path,DataViews.Bytes).open(AllBytes,context)
    try ArrowBlocks.consume(stream,new DataConsumer[ByteChunk] {
      def start(descriptor:DataDescriptor[ByteChunk]):Unit=()
      def accept(chunk:ByteChunk):Unit=out.write(chunk.bytes,chunk.offset,chunk.length)
    }) finally stream.close()
  }
```

```scala
ViewExamples.bytes(access, "/weather-observations.jsonl", context, System.out)
```
The same method reads JPEG: supply the actual image Item path. Original bytes differ from the JSONL bytes derived for records-only Items; the derived view does not reconstruct an original file.

## records: CSV or JSONL table rows

`Scan(limit = Some(3L))` limits the whole view stream to three rows, not three per batch. The consumer may receive one or more batches, or schema only. This example uses ordinary columns; dictionary-encoded columns also require `batch.dictionaries` to interpret indices.

```scala
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
```

```scala
ViewExamples.records(access, "/weather-observations.jsonl", context)
```
## array: NetCDF variables and FITS images

Each block retains multidimensional `origin`, `shape`, `stride` and numeric encoding. Values inside a block use row-major order; arrays are not implicitly flattened to records. This example prints block metadata without collecting the complete array.

```scala
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
```

```scala
ViewExamples.arrays(access, "/scientific/climate.nc/temperature", context)
ViewExamples.arrays(access, "/scientific/sky.fits/hdu0", context)
```
Replace `ArraySelection()` with `ArraySelection(Some(ArraySection(...)))` to select a region. For a two-dimensional variable, its first cell uses `origin=Vector(0,0)`, `shape=Vector(1,1)`, `stride=Vector(1,1)`; rank and bounds must match the variable. FITS table HDUs expose records and image HDUs expose arrays: inspect metadata and capabilities first.

## sequence: FASTA sequences

A sequence emits its ID, symbol offset, current symbols and a `last` marker. Offsets count symbols, not file bytes; one sequence can span multiple blocks. `SequenceSelection` can select IDs, offsets and lengths.

```scala
def sequences(access:DatasetAccess,path:String,context:StreamContext):Unit={
    val stream=access.requireView(path,ScientificViews.Sequences).open(SequenceSelection(),context)
    try ArrowBlocks.consume(stream,new DataConsumer[SequenceChunk] {
      def start(descriptor:DataDescriptor[SequenceChunk]):Unit=println(descriptor)
      def accept(chunk:SequenceChunk):Unit=println(s"${chunk.id} offset=${chunk.offset} last=${chunk.last}: ${chunk.symbols}")
    }) finally stream.close()
  }
```

```scala
ViewExamples.sequences(access, "/scientific/genome.fa/sequences", context)
```
## One ArrowDataStream interface

`ArrowBlocks` is a convenience projection of standard Arrow representations, not a second wire encoding. Every view can use this common consumer to inspect its schema and batch sizes directly:

```scala
def inspect[S](access:DatasetAccess,path:String,kind:DataViewType[S],selection:S,context:StreamContext):Unit={
    val stream=access.requireView(path,kind).open(selection,context)
    try stream.consume(new ArrowConsumer {
      def start(schema:Schema,dictionaries:DictionaryProvider):Unit=println(schema.toJson)
      def accept(batch:ArrowBatch):Unit=println(s"batch rows=${batch.root.getRowCount}")
    }) finally stream.close()
  }
```

```scala
ViewExamples.inspect(access, "/weather-observations.jsonl", DataViews.Records, Scan(), context)
ViewExamples.inspect(access, "/scientific/climate.nc/temperature", ScientificViews.Arrays, ArraySelection(), context)
```
Bytes have a `data: binary` column; records have business columns; arrays use `origin/shape/stride/values`; sequences use `id/offset/symbols/last/description`. An array stream row represents a multidimensional block, not one scalar observation. See [Arrow view-stream contracts](arrow-streams.md) for fields and descriptor metadata.

## Encode a view stream as IPC Stream

The output can be a socket or response body; there is no need to collect the complete result or save a file first:

```scala
def send(stream: ArrowDataStream, output: OutputStream, context: StreamContext): Unit =
  try ArrowIpc.writeStream(stream, output, context)
  finally stream.close()

// Receiver: input is an InputStream, consumer is an ArrowConsumer.
val received = ArrowIpc.readStream(input, context)
try received.consume(consumer)
finally received.close()
```
Writers leave the caller's output open; readers own and close their input. For explicit seekable storage, use `ArrowIpc.writeFile` and `readFile`; engine-managed retention uses [execution policies](materialization.md). Same-process consumers can pass Arrow batches directly without IPC serialization.

## Lifetime, backpressure and cancellation

`open` creates a single-consumption stream. `start` occurs once, followed by zero or more `accept` callbacks. Synchronous callbacks return before the producer continues, so slow consumers apply backpressure. Roots, vectors, dictionaries and scientific-block buffers are borrowed and must not survive the callback. For asynchronous processing use `batch.copyTo(allocator)`, close that copy and keep queues bounded.

Pass the host cancellation check through `StreamContext.check`. Callback failure terminates consumption and releases producer resources; still close streams and access handles in `finally`, then close the allocator. An empty stream still has a schema, unlike an unsupported view. `access.view` returns `None` for an unsupported view; `requireView` reports an explicit error.
