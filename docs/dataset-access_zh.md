# 数据集、Item 与流访问

XStream 使用 `DataCatalog → PublishedDataset → DatasetAccess → DataView → ArrowDataStream`。宿主提供目录；内嵌引擎、Runner 和 Designer 复用相同的数据接口。应用通过这些接口管理和读取数据，无需依赖具体存储后端。

## 资源所有权与能力

| 接口 | 职责 |
|---|---|
| `DataCatalog` | 列出数据集，打开由调用方负责关闭的数据集租约 |
| `PublishedDataset` | 管理数据集资源，创建请求作用域的访问对象 |
| `DatasetAccess` | 列出 Item、描述元数据并提供支持的视图 |
| `ItemAccess` | 可选的对象、文件或游标后端句柄 |
| `DataView` | 将选定视图打开为 Arrow 流 |
| `ArrowDataStream` | 消费时产生结构和有界 Arrow 批次 |

数据集描述是包含 `title` 与 `description` 的 JSON 字符串。Item 路径是逻辑路径，不是存储凭据或物理文件名。Folder Item 支持列出子项；普通目录不提供内容视图。结构化文件和归档可以同时支持列出子项及原文件 bytes 视图。未知的大小、数量和时间戳保持未知。

## 读取视图

先描述 Item，再选择其声明支持的视图。使用对应选择条件打开：records 使用 `Scan`，bytes 使用 `AllBytes`；科学视图保留声明的维度与结构，不隐式展平数组。完整读取示例见 [Scala 视图示例](data-view-examples_zh.md)，CSV、压缩文件及科学数据 Item 见[数据模型](data-model_zh.md)。

`ArrowDataStream.consume(ArrowConsumer)` 同步执行。生产者调用 `start(schema, dictionaries)`，再为每个借用的批次调用 `accept(ArrowBatch)`。消费者若需在回调结束后保留数据，必须复制。读取和输出前检查取消状态；成功与失败时都关闭流、游标及自己持有的 Arrow 缓冲区。不要关闭调用方的 allocator 或应用连接池。详见[流契约](data-streams_zh.md)。

## 实现后端

实现 `DatasetProvider`，必要时实现 `ItemDatasetAccess` 的 `list()`、`list(path)`、`describe(path)` 和 `openItem(path)`。返回的 `ItemAccess` 声明支持的视图并实现流消费。后端插件负责认证、分页、超时、依赖库与资源清理。添加后端不需要修改客户端或增加核心格式分支。

在 `META-INF/services/xstream.data.DatasetProvider` 注册实现。将 JAR 和依赖安装到宿主的 `plugin_directory`。动态目录可以直接返回 `PublishedDataset`，也可调用 `CatalogContext.createDataset`。详见[数据集提供器](dataset-providers_zh.md)、[动态目录](dataset-catalogs_zh.md)与[插件架构](plugins_zh.md)。

MongoDB 集合和 S3 对象是可扩展后端的例子，并非已内置的生产连接器。连接器必须明确声明结构、视图能力及扫描行为。

## 派生 JSONL bytes

`ItemByteViews.wrap` 为仅提供 records 的 Item 增加 bytes 视图。需要此策略的宿主使用该公共适配器；插件仍可只实现 records。原生 bytes 始终优先。此转换不会将科学数组转换成记录。

派生表示为不含 BOM 的 UTF-8 JSONL，每条记录一个对象，并以换行结束：

```jsonl
{"station":"A","temperature":20.5,"quality":null}
{"station":"B","temperature":18.0,"quality":"ok"}
```

空流产生零字节。嵌套对象和列表保留结构；二进制字段变为 Base64 字符串。非有限数变为字符串。不支持的值显式报错。消费之前不知道派生大小；调用方不应为了显示元数据而扫描内容。

转换输出有界字节块，一条记录或一个 UTF-8 字符可能跨批次拆分，必须拼接流后解码。转换支持背压和取消，不缓存整个数据集，也不将转换结果暂存到磁盘。如果输出部分数据后消费失败，已输出内容是不完整结果。
