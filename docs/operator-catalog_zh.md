# 协作示例使用的算子库

[English](operator-catalog.md)

协作示例部署提供以下六个算子，推荐版本为 `2.0`，使用 Scala 和 `jvm` 执行器。每份源码实现处理、结构推导和有界输出样例。Scala、Arrow 和 XStream SDK 由 `jvm-arrow@2.1` 提供；`pom.xml` 声明 Commons CSV、SQLite JDBC，由仓库通过 `dependencies.provider: jvm` 锁定到共享依赖环境。算子 JAR 仅包含业务代码。此处描述示例部署，并不表示新仓库自动安装这些算子。

```bash
curl http://localhost:8820/v1/operators
```

该接口和仓库首页显示实际已发布版本。`item_source@1.0` 和 `client_sink@1.0` 是已发布到仓库的流端点算子。源码树中的 Scala、Java、原生命令、常用算子集合及其他 PiFlow 移植代码属于可构建示例，不等于当前仓库已发布的能力。

## Galaxy 流式工具

示例仓库还发布了 200 个 Scala 文本、表格和生物信息工具。20 个文本与基础基因组工具的版本为 `1.0`（`galaxy_tabular_to_fasta` 为 `1.0.1`）：`galaxy_change_case`、`galaxy_condense_characters`、`galaxy_convert_delimiters`、`galaxy_cut_columns`、`galaxy_merge_columns`、`galaxy_add_column`、`galaxy_fasta_length`、`galaxy_fasta_filter_length`、`galaxy_fasta_to_tabular`、`galaxy_remove_beginning`、`galaxy_head`、`galaxy_tail`、`galaxy_tabular_to_fasta`、`galaxy_sam_filter_flags`、`galaxy_sam_to_interval`、`galaxy_trim`、`galaxy_make_windows`、`galaxy_vcf_extract`、`galaxy_fastq_to_fasta`、`galaxy_fastq_to_qual`。它们通过 Arrow 算子协议读写受内存限制的 UTF-8 bytes 流，并提供结构推导及输出样例。读取 Item 时选择 bytes 视图。参数表、完整 Job、上游归属、行为差异和可复现测试见 [Galaxy 示例](../examples/operators/galaxy/README_zh.md)。运行时采用 `jvm-arrow@2.1`，无额外依赖；通过源码提交和管理员审核发布。

[Galaxy 扩展参考](galaxy-operators_zh.md)列出另外 180 个适配器，涵盖排序、去重、列重排、字面量检索替换、色彩空间解码、双端读段、pileup、pgSnp、反向互补、翻译、密码子及碱基统计、模式匹配、FASTA 统计、BED 区间分析、FASTQ 质量处理、seqtk 序列处理及表格科学分析。每个适配器均有完整可运行 Job 及明确的支持范围。[目录核验报告](../examples/operators/galaxy/verification-public-catalog.json)记录公开定义、源码归档及产物校验和。

## 端口、参数和执行方式

以下算子均使用输出端口 `result`。输出描述由 `inferOutputs(inputs, params)` 给出，清单只声明输入；执行作业时不自动调用推导方法。

| 算子 | 输入 → 输出 | 参数示例 | 执行方式与限制 |
|---|---|---|---|
| `piflow_csv_parser@2.0` | `source: bytes` → `records` | `{"header":true,"delimiter":","}` | 按批解析 UTF-8 CSV；列值均为字符串，不自动猜测类型。无表头示例：`{"header":false,"schema":"station_id,temperature,quality"}` |
| `piflow_filter@2.0` | `source: records` → `records` | `{"condition":"CAST(temperature AS REAL) >= 30"}` | 逐批筛选；使用 SQLite 条件表达式，保留输入 schema |
| `piflow_execute_sql_stop@2.0` | `source: records` → `records` | `{"viewName":"observations","sql":"SELECT station_id, COUNT(*) AS n FROM observations GROUP BY station_id"}` | 收集输入后执行 SQLite SQL；输入与输出均有限额，不是常量内存流算法 |
| `piflow_merge@2.0` | `left: records`, `right: records` → `records` | `{}` | 先输出 left，再输出 right；按名称合并字段，追加右侧独有字段，缺失值填 null；拒绝不兼容类型，不去重、不排序 |
| `piflow_join@2.0` | `left: records`, `right: records` → `records` | `{"joinMode":"inner","correlationColumn":"station_id"}` | 收集两侧输入后关联；模式为 `inner`、`left`、`right`、`full_outer`；右侧同名非键列追加 `_right`，仍有歧义则报错 |
| `piflow_csv_save@2.0` | `source: records` → `bytes`（CSV） | `{"header":true,"delimiter":","}` | 输出 CSV 字节流，**不在服务器保存 CSV 文件**；仅支持标量列 |

SQL 与 Join 的输入收集上限为 128 MiB（实现会估算内存占用）；实际内存还包含 Arrow、JVM 对象和算法开销。SQLite SQL 不等于 Spark SQL，不能直接使用任意 Spark 函数。具体契约和源码见 [算子开发指南](operator-authoring_zh.md)、[specs.json](../examples/operators/piflow/specs.json) 与 [operators](../examples/operators/scala-piflow/)。

## 输入输出范例

CSV 输入：

```csv
station_id,temperature,quality
S001,32.5,0
S002,18.0,0
S003,35.0,1
```

经 CSV parser 后，records 的 schema 为三个字符串列。Filter 使用：

```json
{"condition":"quality = '0' AND CAST(temperature AS REAL) >= 30"}
```

输出仍是三个字符串列，仅保留 `S001`。经 CSV save 后得到：

```csv
station_id,temperature,quality
S001,32.5,0
```

需要数值列时，通过 SQL 显式转换，例如 `SELECT station_id, CAST(temperature AS REAL) AS temperature FROM observations`，不要假定 CSV parser 自动转换类型。

## Shell 使用

连接节点并选择包含 `/observations` 的气象数据集后：

```text
run piflow_csv_parser@2.0 --input source=/observations --view bytes
```

如果 Item 已提供 records 视图，可直接过滤：

```text
run piflow_filter@2.0 --input source=/observations --view records --params '{"condition":"CAST(temperature AS REAL) >= 30"}'
```

组合解析、过滤与 CSV 输出时，使用以下 Job。节点示例 URI 为 `dacp://localhost:18861/weather/observations`；提交 Union 时替换成实际目录返回的 Union 公开 URI，不使用成员内部 URI。Union 规划步骤的执行位置。

```yaml
name: High-temperature observations
steps:
- id: read
  operator: item_source@1.0
  inputs: {}
  params:
    uri: $INPUT_URI
    view: bytes
- id: parse
  operator: piflow_csv_parser@2.0
  inputs:
    source: read
- id: filter
  operator: piflow_filter@2.0
  inputs:
    source: parse
  params:
    condition: "quality = '0' AND CAST(temperature AS REAL) >= 30"
- id: export
  operator: piflow_csv_save@2.0
  inputs:
    source: filter
- id: client_sink
  operator: client_sink@1.0
  inputs:
    result: export
```

保存为 `weather.yaml`，另建 `variables.yaml`：

```json
{"INPUT_URI":"dacp://localhost:18861/weather/observations"}
```

```text
submit weather.yaml --vars variables.yaml
```

默认结果通过 stream 返回；选定边界的物化、重试和超时由独立的[执行策略](materialization_zh.md)控制。算子内部收集数据与引擎保存输出是不同机制。

## 发布与核对

仓库浏览、下载公开；发布需要 token。各算子的构建、测试和逐个上传命令见 [算子发布指南](operator-submission_zh.md)，HTTP 上传契约见[仓库手册](https://github.com/rdcn-link/xstream-operator-repository/blob/main/docs/browsing_zh.md)。发布后先核对 `/v1/operators`，再核对对应版本的页面、参数、源码和许可证。

## 脚本算子

示例仓库另提供 `script_text_replace@1.0`、`script_line_number@1.0`、`script_jsonl_project@1.0` 和 `script_records_to_jsonl@1.0`。它们使用 `python-arrow@2.0` 和同一锁定依赖环境，不包含 SDK，不调用外部命令。参数、端口、限制和完整 Job 见[脚本算子示例](../examples/operators/scripts/README_zh.md)。

Merge 要求先解码字典列。Join、SQL 和 CSV 输出接收标量列。CSV parser 可从显式的无表头结构或上游字节样例推导字符串列；Join 推导键、重名后缀及外连接可空性。SQL 表达式的类型可能依赖实际数据，处理前保持未知。

Galaxy FASTQ 算子：`galaxy_fastq_to_fasta@1.0`、`galaxy_fastq_to_qual@1.0`，支持显式 Solexa64、Sanger33 和整数质量编码，输入输出均为 bytes 流。
