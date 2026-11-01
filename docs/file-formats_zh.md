# 文件格式与容器参考

[数据模型](data-model_zh.md) · [读取示例](data-view-examples_zh.md) · [开发格式插件](format-handlers_zh.md)

这里集中列出内置文件读取器的格式、参数和运行限制。先按数据模型选择 Item 和视图，再查阅对应格式。科学格式的结构与读取示例见 [DataView 示例](data-view-examples_zh.md)。

## 格式与视图

format 标识源数据表示方式，view 标识读取方式。客户端使用服务器公布的 `views`，包括以后新增的格式，以视图声明确定访问能力。格式名称使用小写。

| 来源 | Format | Views |
|---|---|---|
| `.csv` | `csv` | `records`, `bytes` |
| `.jsonl`, `.ndjson` | `ndjson` | `records`, `bytes` |
| `.json` | `json` | `bytes` |
| `.zip`, `.ZIP` | `zip` | `bytes` |
| `.tar` | `tar` | `bytes` |
| `.gz` | `gzip` | `bytes` |
| `.tar.gz`, `.tgz` | `tar-gzip` | `bytes` |
| `.xls`, `.xlsx` | `excel` | `records`, `bytes` |
| XStream Parquet | `parquet` | `records`, `bytes` |
| SQL 表 | `sql` | `records`, `bytes` |
| 其他文件 | `bin` | `bytes` |

注册表优先使用显式 `format`，否则依次匹配归档组合后缀、已注册的文件签名和不区分大小写的扩展名，最终回退为 `bin`。检测最多读取八个签名字节，不验证整个文件；结构化内容错误在打开记录视图时报告。已识别文件仍可通过 bytes 原样读取。Parquet 目前要求 XStream Arrow schema footer 元数据。普通 JSON 仅提供 bytes；对于按行存储对象的 `.json` 文件，配置 `format: ndjson` 即可启用记录视图。SQL 的格式为 `sql`，bytes 视图使用 Arrow IPC。

### ZIP 归档浏览

将 ZIP 文件映射为普通文件别名，服务器自动将其发布为 `kind=folder`、`format=zip`，提供原始归档的 `bytes` 视图。`list("/archive")` 列出归档的直接子项，`list()` 包含全部归档条目；缺失的父集合自动补齐。条目按扩展名选择格式，打开时使用现有格式处理器检测和读取。ZIP 内的 ZIP 暂作为 `bin` data Item，不递归展开。所有 folder 的修改时间、大小及记录数仍为未知。

```yaml
datasets:
  public:
    provider: mixed
    items:
      /archive: {file: /private/reports.ZIP}
```


列举只读取 ZIP 目录，不解压条目。打开、推断 schema 或计数时，仅将所需条目解压到服务器临时文件，完成、失败或取消后清理；因此大条目的首次响应需要等待解压。每个归档最多 10,000 个条目，单条目解压上限 512 MiB，目录声明的总大小上限 2 GiB。绝对路径、路径穿越、重复条目、文件与父集合冲突均被拒绝；不解压到源目录，也不跟随归档内符号链接。`dacp-shell` 可用 `ls /archive` 浏览、用 `cat /archive hex` 预览原始归档。Python 和 Scala SDK 均按 advertised views 打开可读集合；普通集合没有读取视图。

### TAR、GZIP 与压缩 TAR

`tar`、`gzip` 和 `tar-gzip` 与 `zip` 一样发布为 folder，仅提供原始文件的 bytes 视图。`.tar.gz` 和 `.tgz` 识别为单个压缩 TAR，直接列出 TAR 条目，没有中间 `.tar` 子项。扩展名不区分大小写；显式 `format` 可覆盖后缀检测。

普通 `.gz` 提供一个解压后的 data 子项。公开别名 `/orders.csv.gz` 对应 `/orders.csv.gz/orders.csv`；别名不以 `.gz` 结尾时使用 `content`。GZIP 头中的私有文件名不参与命名，子项的修改时间和列举大小保持未知；按内容格式提供 records 和 bytes。连续 GZIP members 被合并为同一个解压流。


TAR 列举顺序扫描归档；压缩 TAR 需要解压扫描。为验证整个目录及校验和，读取选定条目也会扫描完整归档，再只暂存所需文件供现有读取器使用。GZIP 列举不解压数据正文，读取时解压到临时文件。完成、异常或取消均清理临时文件；扫描及解压过程检查取消。归档大小没有跨请求索引缓存。

与 ZIP 一样，最多 10,000 个条目、单条目解压最多 512 MiB、声明总大小最多 2 GiB；TAR 的解压流另外最多允许 16 MiB 的头、填充和尾部开销。TAR 支持普通文件及目录，拒绝符号链接、硬链接、稀疏文件、设备及其他特殊条目，校验头与 GZIP CRC。常见的条目前缀 `./` 及根目录条目会被规范化，其余路径穿越、重复名称及文件/父目录冲突均被拒绝。folder 的大小和记录数仍未知，时间属性仅在源提供时公开；嵌套归档保持二进制 data，不递归展开。BZ2、XZ 和 Zstandard 解压尚未实现。

### 读取选项与 Excel

文件映射支持以下私有读取选项：

```yaml
datasets:
  office:
    provider: mixed
    items:
      /orders:
        file: /private/sales.xlsx
        format: excel
        options: {sheet: Orders, header_row: 2}
        schema:
          - {name: id, type: int64}
          - {name: amount, type: float64}
      /events:
        file: /private/events.json
        format: ndjson
```

`options.sheet` 和 `options.header_row` 仅用于 Excel；表头行号从 1 开始，默认值为 1。单工作表文件自动选择唯一工作表；多工作表文件必须显式指定。可用多个逻辑别名分别发布工作表。每个别名的 bytes 返回原始完整工作簿，records 和 count 仅对应所选工作表。工作表绑定及底层文件名不会出现在元数据或 ticket 中。

Excel 使用 Apache POI 5.5.1 的 XLS/XLSX 事件读取接口。空表头生成 `column_N`，重复名称依次添加 `_2`、`_3`，缺失单元格返回 null。空数据行跳过，超出表头宽度的数据报错。公式只读取缓存结果，不重新计算；可识别的缺失缓存返回 null。错误单元格、加密或无效工作簿返回格式错误。整数且未越界的数字单元格使用 int64，其余数字使用 float64，布尔值及字符串保留其值。Excel 数字日期保留序列值，不自动推断地区或时区。可选 `schema` 通过有序的 `name`/`type` 配置列，支持 `int64`、`float64`、`bool` 和 `utf8`/`string`。未配置时采样不超过 `batch_size * infer_sample`，后续类型冲突报错，不修改既定 schema。

列举不解析 CSV、NDJSON 或 Excel 记录；除发布时已填充的记录数外，其记录数保持未知。打开记录视图时通过有限采样推断 schema；除可选的启动计数外，只有显式 `count` 扫描文件，Parquet 可直接读取 footer 中的数量。Excel 解析队列最多保留 16 行，关闭或取消时停止解析；schema、当前 batch、单个单元格及 POI 共享字符串表还会占用内存，大型共享字符串表仍可能需要较多内存。不会在内存中构建完整工作表。

读取器实现及注册参见[格式处理器开发](format-handlers_zh.md)。


## Item 元数据

`creationTimeMs`、`lastModifiedTimeMs` 是存储源报告的 UTC Unix 毫秒时间戳；`totalSizeBytes` 是内容字节数，`recordCount` 是精确记录数。未知值为 `None`，不同于已知的 0。时间不可用时不以当前时间代替。物理目录和可读归档根可保留源时间，虚拟目录由 provider 决定；目录大小和记录数未知，列举不扫描后代以求和。

普通列举不计数 CSV、NDJSON、Excel 或 SQL；显式 `count` 才扫描记录或执行查询。Parquet 可读取 footer。元数据是观测值，不与后续读取组成原子快照。

### 发布时填充记录数

配置 `count_file_records_on_startup: true` 后，服务器在加载配置时对 mixed 文件映射和目录初次发现的记录文件执行一次精确计数并保存结果，普通浏览复用该结果。默认关闭。计数与文件大小和修改时间绑定；计数期间或之后检测到文件变化时，结果为未知。重启会重新计数，显式 `count` 仍按当前内容计算且不修改保存的元数据。该选项不计数 SQL、归档子项或 folder；启用时无效记录文件会导致启动失败。

`plugin_directory` 可选指定插件及依赖 JAR 所在目录，在注册表初始化前加载，修改后需要重启。读取器专用参数放入 `options`，由处理器自行校验。独立 API 与结构化文件契约见 [插件指南](plugins_zh.md)。

