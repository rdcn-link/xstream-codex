# DataView 与视图流示例

[English](data-view-examples.md)

以下示例使用本仓库的 JSONL、NetCDF、FITS、FASTA 文件。CSV 与 JSONL 使用同一种 records 接口；JPEG 使用 bytes，不提供 image 视图。完整可编译程序见 [ViewExamples.scala](../examples/xstream/ViewExamples.scala)。

## 初始化本地目录

从仓库根目录运行。`directory`、`static`、基础格式、NetCDF、FITS、FASTA 及科学视图插件必须在运行时类路径上；标准 Runner/Designer 发行包包含这些实现。仅引用 `xstream-data-core` 不会自动安装格式解析器。

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
`access.list("/")` 列出直接子 Item，`access.list("/scientific")` 展开目录。通过 `access.describe(path)` 获取元数据、创建时间和最后修改时间；通过 `plugins.views(item)` 查看支持的视图。时间未知时为 `None`，读取元数据不消费内容流。

## bytes：JSONL、CSV、JPEG 的原始字节

每个 `ByteChunk` 只包含当前块，不是完整文件。必须使用 `offset` 和 `length`；UTF-8 字符可能跨块，不能把每块独立解码为字符串。以下代码直接写入调用方提供的输出流，不关闭该输出流。

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
同样的方法可读取 JPEG；只需改成实际图片的 Item 路径。原始 bytes 与 records-only Item 派生的 JSONL bytes 不同：派生视图不是原始文件的重建。

## records：CSV 或 JSONL 表记录

`Scan(limit = Some(3L))` 对整个视图流限制三行，而非每批三行。回调可能收到一个或多个批次，也可能只收到 schema。示例使用普通列；字典编码列还需要结合 `batch.dictionaries` 解释索引。

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
## array：NetCDF 变量与 FITS 图像

每个块包含原始多维坐标 `origin`、块尺寸 `shape`、步长 `stride` 和数值编码。块内数值按行主序排列，不隐式转为平面记录。示例仅打印块元数据，不收集整幅数组。

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
可将 `ArraySelection()` 替换为 `ArraySelection(Some(ArraySection(...)))`。例如二维变量的起始单元可用 `origin=Vector(0,0)`、`shape=Vector(1,1)`、`stride=Vector(1,1)`；秩和边界必须符合该变量的维度。FITS 表 HDU 使用 records，图像 HDU 使用 array，应先查看元数据和能力。

## sequence：FASTA 序列

序列以 ID、符号偏移、当前符号块和 `last` 标记输出。偏移单位为符号，不是文件字节；一个序列可以跨多个块。`SequenceSelection` 可选择 ID、偏移和长度。

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
## 同一种 ArrowDataStream 接口

上述 `ArrowBlocks` 是标准 Arrow 表示的便利投影，不是第二套传输编码。所有视图都可直接使用以下公共消费者，按批查看 schema 和行数：

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
bytes 的 Arrow 列是 `data: binary`；records 为业务字段；array 使用 `origin/shape/stride/values`；sequence 使用 `id/offset/symbols/last/description`。array 流中的“一行”是一个多维块，不是一个标量观测值。完整字段和描述元数据见 [Arrow 视图流契约](arrow-streams_zh.md)。

## 将视图流编码为 IPC Stream

输出可以是 socket 或响应体，无需先收集完整结果或保存文件：

```scala
def send(stream: ArrowDataStream, output: OutputStream, context: StreamContext): Unit =
  try ArrowIpc.writeStream(stream, output, context)
  finally stream.close()

// Receiver: input is an InputStream, consumer is an ArrowConsumer.
val received = ArrowIpc.readStream(input, context)
try received.consume(consumer)
finally received.close()
```
写入器不关闭调用方的 output；读取器拥有 input 并负责关闭。需要显式保存可随机读取的结果时，可使用 `ArrowIpc.writeFile` 和 `readFile`；引擎管理的持久化请使用[执行策略](materialization_zh.md)。同进程消费者可直接传递 Arrow 批次，无需 IPC 序列化。

## 生命周期、背压与取消

`open` 创建单次消费的流；`start` 恰好一次，之后是零个或多个 `accept`。每次回调同步返回后，生产者才继续，因此慢消费者会施加背压。回调中的 root、vector、字典及科学块缓冲区均为借用，不能保留到下一次回调。需要异步处理时使用 `batch.copyTo(allocator)`，并关闭该副本；不要用无界队列。

`StreamContext.check` 可传入宿主取消检查。回调抛错会终止消费并释放生产者资源，仍应在 `finally` 中关闭 stream 和访问句柄，最后关闭 allocator。空流仍然有 schema；它不同于不存在的视图。不存在的视图可通过 `access.view` 得到 `None`，或由 `requireView` 明确报错。
