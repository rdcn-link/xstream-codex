# Galaxy 流式算子

[English](README.md)

本目录提供 200 个通过源码发布的 Scala 算子，将经过审阅的 Galaxy Devteam、Community 和 IUC 工具适配为 Arrow bytes 流。本页介绍20 个文本与基础基因组处理适配器；[扩展参考](../../../docs/galaxy-operators_zh.md)介绍其余 180 个文本、表格和生物信息适配器、支持范围及限制。原始脚本、XML 定义、标准测试数据和许可保留在 `upstream/`、`upstream-extended/` 与 `upstream-dependencies/`，对应清单记录固定版本及 SHA-256。这些是选定工具的独立适配实现，并非完整 Galaxy 工具集合或 Galaxy 工作流导入器。

## 选择算子

其余 180 个适配器见 [Galaxy 流式算子适配器](../../../docs/galaxy-operators_zh.md)。

以下20个算子版本为 `1.0`（`galaxy_tabular_to_fasta` 为 `1.0.1`），包含一个 `source` bytes 输入和一个 `result` bytes 输出，文本编码为 UTF-8。它们通过 JVM SDK 提供结构推导和受大小限制的输出样例。`bytes` 输出表达表格文本，不会声明 Arrow records schema；需要 records 时应连接合适的文本/TSV 解析算子。共享运行时为 `jvm-arrow@2.1`，产物是薄 JAR，无新增运行时依赖。

| 算子 | 参数（默认值） | 行为 / 上游工具 |
| --- | --- | --- |
| `galaxy_change_case` | `columns: c1`、`delimiter: tab`、`casing: upper` | 修改所选字段大小写，输出 TSV / `change_case/changeCase.pl` |
| `galaxy_condense_characters` | `character: tab` | 压缩连续重复字符，输出文本 / `condense_characters/condense_characters.pl` |
| `galaxy_convert_delimiters` | `character: tab` | 去除每行首尾空白，将连续分隔符转换为制表符，输出 TSV / `convert_characters/convert_characters.py` |
| `galaxy_cut_columns` | `columns: c1`、`delimiter: tab` | 选择、重排、重复列，跳过 `#` 行，缺失字段填 `.`，输出 TSV / `cut_columns/cutWrapper.pl` |
| `galaxy_merge_columns` | `columns: c1` | 将所选字段无分隔拼接后附加一列，忽略缺失字段，保留注释和空行，输出 TSV / `merge_cols/mergeCols.py` |
| `galaxy_add_column` | `value: "1"`、`iterate: false` | 附加常量；启用递增后非负整数从该值递增，其他值生成 `value-0`、`value-1` 等，输出 TSV / `add_value/fixedValueColumn.pl` |
| `galaxy_fasta_length` | `keep_first: 0`、`id_only: false` | 统计每条序列字符数，输出标题/标识与长度 TSV / `fasta_compute_length/fasta_compute_length.py` |
| `galaxy_fasta_filter_length` | `min_length: 0`、`max_length: 0` | 保留长度在闭区间中的序列，最大值为零表示无上限 / `fasta_filter_by_length/fasta_filter_by_length.py` |
| `galaxy_fasta_to_tabular` | `keep_first: 0`、`description_columns: 1` | 输出标题字段和拼接序列 TSV / `fasta_to_tabular/fasta_to_tabular.py` |
| `galaxy_remove_beginning` | `lines: 10` | 删除前 N 行，保留剩余行的换行格式 / `remove_beginning/remove_beginning.pl` |
| `galaxy_head` | `lines: 10` | 输出前 N 行，并消费剩余输入 / `show_beginning/headWrapper.pl` |
| `galaxy_tail` | `lines: 10` | 通过受限滚动缓冲区输出最后 N 行 / `show_tail/tailWrapper.pl` |
| `galaxy_tabular_to_fasta` | `title_columns: "1"`, `sequence_column: 2` | 标题列以下划线拼接并输出 FASTA，缺少选定列时跳过该行 / `tabular_to_fasta/tabular_to_fasta.py` |
| `galaxy_sam_filter_flags` | `require_flags: 0`, `exclude_flags: 0` | 要求指定标志位全部置位、排除位全部未置位，省略头部 / `sam_bitwise_flag_filter/sam_bitwise_flag_filter.py` |
| `galaxy_sam_to_interval` | `print_all: false` | 将已映射比对输出为零起点、左闭右开区间 / `sam2interval/sam2interval.py` |
| `galaxy_trim` | `column: 0`、`start: 1`、`end: 0`、`ignore_prefixes: ""`、`fastq: false` | 裁剪整行或指定字段 / `trimmer/trimmer.py` |
| `galaxy_make_windows` | `window_size: 500`、`offset: 0`、`start_column: 2`、`end_column: 3` | 完整固定窗口或滑动窗口 / `windowsplitter/windowSplitter.py` |
| `galaxy_vcf_extract` | 参考序列、区域和标签为空，`quality: 0`、`quality_operator: none`、`pass_only: false` | 筛选变异行并保留头部 / `vcf_extract/extract.py` |

`columns` 从 1 开始，格式如 `c1,c4,c2-c3`，展开后最多 1024 列，最大列号 999999。`delimiter` 可选 `tab`、`comma`、`dash`、`underscore`、`pipe`、`dot`、`whitespace`；`character` 还支持 `space`、`colon`。未知参数、不支持的枚举和错误列配置会在结构推导及执行时失败。`value` 不能包含制表符或换行。参数不是 shell 命令或正则表达式。这些是按分隔符处理的文本工具，不是支持引号的 CSV 工具，不能用来解析带引号的 CSV 字段。

```mermaid
flowchart LR
    A[Item bytes 视图] --> B[统一分隔符]
    B --> C[修改所选列大小写]
    C --> D[附加行标识]
    D --> E[TSV bytes 流]
```

## 执行 Job

发布后，从 XStream 项目根目录执行：

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job.yaml
```

示例读取 `context:/galaxy/input.txt`，将连续逗号转换为制表符，将第一列改为大写，再附加递增标识，输出：

```text
SAMPLE	group	row-0
ALPHA	a	row-1
BETA	b	row-2
```

将 `remote_operator_library_urls` 设置为所用仓库，并按需要配置传输信任。发布无需修改核心注册表，也无需重新构建执行服务器。

## 构建与验证

先将 XStream JVM SDK 发布到本地，然后在本目录执行：

```bash
sbt package 'Test / runMain xstream.galaxy.SafetyCheck'
python3 make-catalog.py
python3 verify.py --docker-prefix '' --report /tmp/galaxy-tests.json
```

验证脚本所用 Python 环境需安装 PyArrow 和 PyYAML。`verify.py` 在本地 Docker 中使用已注册的运行时镜像，执行期间禁用网络。`--docker-prefix` 默认值为本开发机使用的 `sudo -n -u cnic -g docker`；当前用户已有 Docker 权限时使用 `''`。通过 `--runtime IMAGE` 可选择其他兼容镜像。测试比较 28 组 Galaxy 测试数据（其中两个区间预期修正了上游 padding 错误），覆盖 65 个执行用例，包括空输入、两字节 Arrow 分块、Unicode、CRLF、末行无换行；还验证 15 个推导/样例契约、错误参数、非法 UTF-8、超长行和三步算子流水线。`SafetyCheck` 验证取消、I/O 异常传播、参数错误、输出展开限制、FASTA 记录限制和 tail 缓冲区限制。使用 `--artifact FILE --only NAME` 可以在审核前验证单个仓库构建的 JAR。

输入可跨 Arrow 批次连续读取，不会缓存整个 Item。单行限制为 1,048,576 个 UTF-16 字符，所选/生成字段拼接限制为 4,194,304 个字符，参数字符串最多 4096 个字符，所选列最多 1024 个。工作内存受这些单行限制和 SDK 缓冲区约束。输出样例遵守 SDK 字节预算。读取期间和每行处理均检查取消，输入与输出异常传递给引擎。业务逻辑不调用 shell、子进程、网络或应用文件访问。

## 发布源码

```bash
python3 publish.py --url http://localhost:8820 \
  --token-file /secure/publisher.token --output /tmp/galaxy-publication
```

该命令提交 200 份源码归档，由仓库隔离构建 Scala 算子，保持待审核状态。只提交一个工具时，在末尾添加短名称，例如 `cut_columns`。管理员可以增加 `--approve-password-file /secure/admin-password`；该模式等待构建，下载并验证每个候选产物，指定 `Galaxy`、`Text processing`、`Tabular data` 分类（FASTA 工具使用 `Galaxy`、`FASTA`、`Bioinformatics` 分类，SAM 工具使用 `Galaxy`、`SAM`、`Bioinformatics` 分类）和 4 分可信度，再审批发布，并核验公开下载与已测试候选产物一致。测试失败即停止发布。凭据从独立文件读取，不会打包或打印。远程发布必须使用 HTTPS。可信度是评估结果，不是安全保证。审核记录和产物测试报告写入 `--output`。

每份源码归档包含 Scala 实现、上游证据、中英文说明、验证脚本和完整许可文本；发布的 JAR 内包含 `META-INF/LICENSE`、`META-INF/NOTICE` 和对应算子定义。源码公开。仓库管理和构建前提见[源码提交](../../../docs/operator-submission_zh.md)。

## 行为与归属

标准测试数据保持上游行为。有意调整包括：LF/CRLF/CR 均统一为 LF，末行没有换行时不会丢掉最后一个字符；拒绝非法 UTF-8；大小写转换采用不依赖区域设置的 Unicode 规则；`condense_characters` 将连续空白转为一个空格（上游 `s` 选项缺少有效替换分支）；点号压缩输出字面量点号，避免原 Perl 脚本的转义替换结果；分隔符采用可读枚举；错误参数直接失败，不再静默忽略。change/cut 的尾部空字段遵循原 Perl 工具，merge 遵循原 Python 工具。资源限制和取消行为明确。

Galaxy Contributors 保留原始工具与测试数据的版权。Community 与 IUC 的 MIT 许可声明也保留在 `upstream-extended/` 和发布产物中。上游许可将 2024-12-01 前的贡献归为 AFL-3.0，此后的贡献归为 MIT。[LICENSE](LICENSE) 原样包含两种完整许可，混合来源声明为 `AFL-3.0 AND MIT`。另见 [NOTICE](NOTICE)。这些是独立的 XStream 适配实现，不是 Galaxy 官方发行版。

## FASTA 与行选择说明

`keep_first` 按 Unicode 字符截断标题，零表示保留全部；`id_only` 选择标题的第一个单词。FASTA 长度是序列字符数，不是生物学质量或合法性评估。转表格时，`description_columns: 1` 保留完整标题；更大值拆分开头单词，补齐缺失列，并将剩余描述保留在最后一列。序列跨行直接输出，不缓存整个序列。序列出现在 `>` 标题之前时拒绝输入，跳过注释和空行。空 FASTA 不产生记录，不生成虚假的零长度记录。FASTA 输出使用 LF。过滤最多保留一个 8,388,608 字符的记录；超过非零最大长度的记录立即丢弃其序列缓存。按照上游测试数据，只有输入最后一条记录被保留时，该记录末尾才不加换行。这不会将序列强行推导为 records schema。

head/remove-beginning 接受 0–1,000,000 行；tail 接受 0–10,000 行，保留的 UTF-16 字符合计最多 8,388,608 个。这三个工具保留 LF、CRLF、CR 和末行无换行的情况。即使 head 已输出足够行数，也继续消费输入到 Arrow EOS。不调用 `head`、`tail` 或 shell。长度统计与原工具一样去除序列行两端空白；过滤和转表格保留并统计其他序列字符。结构推导和执行均检查参数。

从 XStream 根目录运行第二个完整示例：

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-fasta.yaml
```

```text
seq1	12	sequence-0
seq3	20	sequence-1
```

每个目录条目都包含 `documentation`：所有输入、输出及参数说明、符合 schema 的示例值，以及完整 Job。发布脚本随源码提交这些字段，仓库接收前会校验。`catalog.json` 同时提供 200 个算子定义的本地参考。固定运行时和测试结果见[验证结果](verification.json)。

## SAM 与表格序列示例

`job-sam.yaml` 读取 `data/alignments.sam`，保留正确配对且已映射的 reads，计算基因组区间。使用与其他示例相同的引擎配置运行：

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-sam.yaml
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-tabular-to-fasta.yaml
```

预期区间输出：

```text
#chrom	start	end	strand	read_name
chr1	9	13	+	read1
chr1	19	27	-	read2
```

`galaxy_tabular_to_fasta` 的列号从 1 开始，使用 `1,2` 这样的整数列表，不采用文本列工具的 `c1,c2` 语法。其示例读取 `data/sequence-table.tsv`。第一个标题字段开头的 `>` 会被去掉；缺少选定列时跳过该行，与上游一致。它不检查序列字符是否属于特定字母表。

SAM 工具要求至少 11 个字段和 16 位标志值。与上游筛选工具一致，省略注释和 SAM 头部，因此输出为比对数据行，不是保留完整头部的 SAM 文档。要求置位和排除的掩码不得重叠。区间工具跳过未映射标志以及 `*` 参考序列，要求正数位置和完整有效的 CIGAR，并检查坐标溢出。`M`、`D`、`N`、`=`、`X` 消耗参考序列位置，`I`、`S`、`H`、`P` 不消耗；这修正了上游脚本计入 `P`、遗漏 `=`/`X` 的问题。两个原始区间预期按此修正规则调整，独立边界测试覆盖扩展 CIGAR 与 padding。`print_all: true` 在染色体、起点、终点和链方向之后附加原始 SAM 行。输入行最多 1 MiB 字符，输出行最多 2 MiB 字符；逐行流式处理，并响应取消。

表格/SAM 工具的仓库构建产物测试与独立 runner Job 验证结果见[流工具验证记录](verification-stream-tools.json)。

## 文本裁剪、区间切窗和 VCF 提取

`galaxy_trim` 保留从 `start` 到 `end` 的字符（从 1 开始、包含两端），`end: 0` 保留剩余部分。`column: 0` 裁剪整行，正数裁剪对应的制表符分隔字段。`ignore_prefixes` 是需保留原样的行首字符集合，不是 ASCII 编码或正则表达式。与上游一致，跳过空行、输出 LF 换行。Unicode 位置按字符计算，不按 UTF-8 字节计算。`fastq: true` 保留四行 FASTQ 中交替出现的标识/分隔行，不支持折行 FASTQ。非法位置或不存在的列明确报错。

`galaxy_make_windows` 保留其余字段，输出零起点、左闭右开区间。`offset: 0` 表示不重叠窗口；正数表示滑动步长。仅输出完整窗口；步长大于原区间长度时，只要首个完整窗口放得下，也会输出。短区间不输出窗口。这修正了上游滑动窗口的两项边界错误。非法坐标报错，而非静默丢弃。每个区间最多展开 100,000 个窗口，估算 UTF-16 字符负载最多 64 MiB；每次迭代检查取消，不缓存整个文件。

`galaxy_vcf_extract` 支持精确参考序列名、从 1 开始且包含两端的 `region: "20:80000..100000"`、质量比较、INFO 标签是否存在及 PASS 状态。`keep_info` 要求任一所列精确标签存在，`discard_info` 排除任一所列标签存在的行，两者不能同时使用。条件全空时保留全部记录。缺失质量值 `.` 不满足任何数字比较。除统一 LF 换行外，头部和原始记录字段保持原样；不采用原 Python 工具重排声明、插入 `vcfPytools` 来源行的方式。五组上游 VCF 测试精确比较选中记录，并单独验证输入头部保留情况。在原有 65 个执行用例报告之外，本批新增 19 个用例，见 `verification-trim.json`、`verification-make-windows.json`、`verification-vcf-extract.json`。`GenomicSafetyCheck` 验证非法输入、展开预算、大坐标、精确标签、缺失质量、UTF-8/I/O 异常和窗口展开过程中的取消。

在 XStream 项目根目录运行（当前用户需有 Docker 访问权限）：

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-windows.yaml
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-vcf.yaml
```

第一个 Job 读取 `data/intervals.bed`，每隔两个坐标单位创建四单位窗口，并将特征标签缩短为 `gene`；第二个读取 `data/variants.vcf`，保留选定区域内质量值至少 90 且为 PASS 的变异。不需要额外安装 Galaxy，也无新增运行时依赖。

裁剪、切窗、VCF 提取的仓库产物验证及 runner Job 输出核验见[基因组工具验证记录](verification-genomic-tools.json)。

## 格式插件与 bytes 输入

| 源格式 | 现有结构化支持 | 这些算子读取的内容 |
| --- | --- | --- |
| CSV / TSV | 基础分隔记录插件 | 原始 `bytes` |
| FASTA | `xstream-data-fasta` 提供 `sequence` 视图 | 原始 `bytes`，不是已解析的 sequence 视图 |
| FASTQ / SAM / VCF / BED | 尚无专用结构化格式插件 | 通用 `bytes`，由各算子解析内容 |

这些 Job 不以专用格式插件为前提。如果希望数据目录直接提供格式专属 schema、结构化视图，以及供其他算子复用的统一解析能力，再补格式插件更有价值。格式处理器负责解码，算子负责分析；这些算子的接口不要求输入文件路径，也不要求临时中间文件。

## FASTQ 转换

`galaxy_fastq_to_fasta@1.0` 输出 FASTA；`galaxy_fastq_to_qual@1.0` 输出 QUAL 数值质量流。两者只有 `encoding` 参数：默认 `solexa64`（原始 Solexa 分值，不转换为 Phred）、`sanger33` 或 `integers`（-5 至 93）。不猜测编码，不支持额外的偏移标记。严格接受四行记录，校验 @/+ 标题和序列/质量数量；每行最多 1 MiB 字符，内存仅保留当前记录。整数分隔统一为空格，无尾随空格。支持 CRLF 和末行无换行；截断、无效 UTF-8、错误质量值会失败。

示例：`job-fastq.yaml` 从 `context:/galaxy/reads.fastq` 读取 bytes，经 FASTQ→FASTA→序列长度输出 TSV，无中间文件。

上游样例 `2.fastqsolexa` 的行尾空格在黄金测试中清理后比较；QUAL 预期结果也统一分隔空格。这不放宽运行时 FASTQ 校验。

验证与运行：

```bash
cd examples/operators/galaxy
sbt package 'Test/runMain xstream.galaxy.FastqSafetyCheck'
/path/to/python-with-pyarrow verify.py --only fastq_to_fasta --report verification-fastq-to-fasta.json
/path/to/python-with-pyarrow verify.py --only fastq_to_qual --report verification-fastq-to-qual.json
cd ../../..
xstream-engine/target/universal/stage/bin/xstream-runner examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-fastq.yaml
```

## 扩展工具集

新增 180 个适配器的参数范围、内存限制、来源和运行命令见 [Galaxy 算子](../../../docs/galaxy-operators_zh.md)。每个工具均附完整 Job、示例输入和回归预期。源码及许可证随提交归档。

## 组合示例

`job-fastq-quality-pipeline.yaml` 将 Phred+64 转 Phred+33、屏蔽低质量碱基、转为表格并提取序列列。`job-interval-coverage-pipeline.yaml` 对基因组区间排序、合并后计算覆盖度。各步骤使用各自公开的算子程序包和共享 JVM 运行时。其余四个示例覆盖基因型规范化与剂量统计、DNA 编辑与词频、双输入密码子投影与适应指数，以及表连接后数值回归。发布后执行 `python3 verify-pipelines.py` 可验证全部七个组合示例。

## 源码包与示例

源码包包含完整 Scala 源码、契约、测试、许可和固定版本上游证据，以及当前算子的生成示例。执行 `python3 extend-catalog.py`，再执行 `python3 make-catalog.py`（需要 PyYAML），即可重建全部输入和 Job 示例。每份源码提交均遵守仓库归档限制。
