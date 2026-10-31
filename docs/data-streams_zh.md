# 数据视图与 Arrow 流

[English](data-streams.md)

DataCatalog 包含 Dataset，每个 Dataset 暴露逻辑 Item。Item 可以支持浏览、读取，或同时支持两者。文件容器可以同时暴露原始字节和子项。folder 状态本身不决定其内容是否可读。

## 公共接口

`DatasetAccess.view(path, DataViewType[S])` 查找可选的 `DataView[S]`。`open(selection, StreamContext)` 返回只能消费一次的 `ArrowDataStream`。所有视图具有相同的输出接口：恰好一次 `start(schema, dictionaries)` 回调，随后零次或多次 `accept(ArrowBatch)` 回调。空流也提供 schema。消费期间 schema 保持不变。

```scala
val stream = access.requireView("/observations", DataViews.Records)
  .open(Scan(), StreamContext(allocator, () => cancellation.check()))
try stream.consume(new ArrowConsumer {
  def start(schema: org.apache.arrow.vector.types.pojo.Schema,
            dictionaries: org.apache.arrow.vector.dictionary.DictionaryProvider): Unit =
    println(schema)
  def accept(batch: ArrowBatch): Unit =
    println(batch.root.getRowCount)
}) finally stream.close()
```

同步回调提供背压。root、向量和字典仅借用至回调返回，随后生产者可以复用它们。需要独立拥有快照的消费者应调用 `batch.copyTo(allocator)`，使用后关闭快照。引用计数本身不能防止复用缓冲区被修改。allocator 由调用者拥有。生产者在消费期间获取资源，在 `finally` 中释放，并在读取和输出前调用 `context.check()`。消费中关闭流会阻止后续回调；任意第三方阻塞 I/O 仍需自行支持取消。

完整的 schema、表示、所有权与 IPC 契约见 [Arrow 视图流](arrow-streams_zh.md)。

## 标准视图

| 标记 | 选择条件 | Arrow 输出 |
| --- | --- | --- |
| `DataViews.Bytes` | `AllBytes` | `data` 行中的 Binary 字节块 |
| `DataViews.Records` | `Scan` | 普通的有类型列 |
| `ScientificViews.Arrays` | `ArraySelection` | 多维块：origin、shape、stride、有类型 values |
| `ScientificViews.Sequences` | `SequenceSelection` | 带偏移和结束标志的命名序列分块 |

数组保留秩与维度，不转换为标量记录。未提供数组切片时选择完整变量，返回的数据块始终具有具体坐标。序列偏移按符号计数。`DataViews.blocks` 和 `ArrowRepresentations.writer` 允许解析器输出有界的 `ByteChunk`、`RecordBatch`、`ArrayChunk` 或 `SequenceChunk`；产生的公共视图仍然输出 Arrow。`ArrowBlocks` 是这些标准表示的便捷投影，不是第二套传输格式。

`Scan` 用于列投影、过滤和全局行数限制。JDBC 与 Parquet 保留其源端优化，其他读取器采用有界过滤。普通 records 读取保留字典。目前显式不支持对字典编码记录进行 scan。`BatchDatasetAccess` 和 `BatchItemAccess` 通过 `BatchReceiver` 适配现有 Arrow 生产者，不需要网络监听器，也不需要 IPC 往返。

原始文件字节优先。SQL 表暴露 Arrow IPC 字节。仅提供 records 的 Item 由 `ItemByteViews` 派生有界 UTF-8 JSONL 字节（`application/x-ndjson`），保留嵌套值并解码顶层字典。字节块不必按行或 UTF-8 字符对齐。错误时不刷新缓冲区中的不完整 JSON。视图转换不会隐式物化输出。

## 科学格式

| 格式 | 浏览与视图 | 保留内容 |
| --- | --- | --- |
| CSV / JSONL | records 与原始 bytes | 有类型列、null 与嵌套值 |
| NetCDF | group 与变量；变量提供 array | 秩、维度、坐标引用、单位、填充值与属性 |
| FITS | HDU；表格 records、图像 array | HDU 身份、头信息、轴、缩放与坐标元数据 |
| FASTA | 原始 bytes 与 sequence | 序列身份、符号偏移、结束标志与有界分块 |
| JPEG | 原始 bytes | 在算子内部解码图像 |

数组 values 是有类型的 Arrow list，每行表示一个多维块。稠密数值采用行优先顺序。坐标 Item 保持为逻辑引用，不在每个块中复制坐标数组。原始数值及缩放、缺失值属性保持显式，不会自动展平或静默转换。

## 插件与网络访问

实现 `DatasetProvider`、`FormatHandler` 或 `StructuredFileHandler`，并在 `META-INF/services/<interface>` 注册公共无参类。第三方依赖与配置校验保留在插件模块。结构化读取器返回其逻辑子对象上的 DatasetAccess，存储路径不能泄漏到逻辑命名空间。参见[插件](plugins_zh.md)。

```yaml
datasets:
  climate:
    provider: my-netcdf
    source:
      root: /srv/climate
```

`my-netcdf` 是外部提供者示例，负责解释 `source` 配置。自定义表示定义共享的 `DataViewType[S]` 标记，直接输出 schema 与 Arrow 批次；增加它不需要核心注册表或输出编码器。标记按实例身份匹配，而不仅仅比较名称。

可选的 `DataViewSupport` 插件解析、输出请求选择条件，并提供有界的结构与样例展示。通过 `META-INF/services/xstream.data.DataViewSupport` 注册。其输出同样是普通 Arrow，不负责载荷字节的编解码。内置 array 与 sequence 支持位于 `xstream-data-scientific-views`。Flight 直接传输这些有类型的列，包括描述元数据与空 schema。Python 的 `as_view`、`as_array`、`as_sequences` 返回 `ArrowViewReader`，迭代得到 PyArrow RecordBatch。

契约测试覆盖空 schema、稳定 schema、边界、借用生命周期、显式快照、失败与取消清理、字典 IPC、选择条件、嵌套浏览及科学结构的精确保留。另见[设计器科学视图](designer-scientific_zh.md)。
