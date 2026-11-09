# Galaxy 流式算子适配器

[English](galaxy-operators.md)

示例仓库已公开发布 50 个不同的 Galaxy 算子，以下列出新增的 30 个适配器。[目录核验](../examples/operators/galaxy/verification-public-catalog.json)记录确切的公开版本及已测试产物哈希。每个算子使用 `source` Arrow bytes 输入和 `result` bytes 输出，不要求文件系统路径或中间文件。流式接口不代表恒定内存：全流排序、反转采用有界缓冲，生物序列算子可能缓冲一条有界记录。规范化文本输出采用 LF；涉及排序时采用 JVM Unicode 字典顺序。

| 算子 | 用途 | 支持范围 |
| --- | --- | --- |
| `galaxy_reverse_lines@1.0` | 反转文本行顺序 | 仅换行分隔；最多缓存 8 MiB 字符和 100000 行；不支持自定义分隔符。 |
| `galaxy_number_lines@1.0` | 为非空文本行编号 | 支持起点、正增量、宽度及 ln/rn/rz 对齐；空行不编号，逻辑分页转义按普通文本处理。 |
| `galaxy_sort_lines@1.0` | 按指定字段稳定排序 | 仅一个完整字段作为键；支持升降序、忽略大小写和表头。数值须为有限十进制或科学计数；不提供本地化、随机或人类可读单位排序。最多缓存 8 MiB 字符及 100000 行。 |
| `galaxy_sort_fields@1.0` | 逐行排序各列 | 与上游一致，先清理行首尾空白，再按字典顺序排序制表分隔的字段。 |
| `galaxy_unique_lines@1.0` | 排序并去重 | 整行或单列为键，保留稳定代表值；支持数值和大小写选项及表头。最多缓存 8 MiB 字符和 100000 行。 |
| `galaxy_unique_consecutive@1.0` | 合并连续重复行 | 可输出次数，或仅保留重复组/单次组；只合并相邻记录，不支持跳过字段或空行分组。 |
| `galaxy_unfold_column@1.0` | 展开一列中的列表 | 非空字面分隔符；每行最多展开 10000 行、估计 16 MiB 字符。修正末列换行处理。 |
| `galaxy_replace_text_in_column@1.0` | 替换指定列的文本 | 仅字面匹配，支持忽略大小写、首次替换、跳过表头；缺失列报错。不支持正则、反向引用或程序。 |
| `galaxy_replace_text_in_line@1.0` | 替换整行文本 | 仅字面匹配，支持忽略大小写、首次替换、跳过表头；不支持正则、sed 程序或替换反向引用。 |
| `galaxy_find_and_replace@1.0` | 查找并替换文本 | 仅字面匹配，支持忽略大小写、首次替换、跳过表头；不执行 Perl、正则或命令。 |
| `galaxy_grep_lines@1.0` | 筛选包含指定文本的行 | 支持反选和忽略大小写；仅字面搜索，不提供正则、上下文行或 HTML 输出。 |
| `galaxy_arrange_columns@1.0` | 按表头重排列 | 可丢弃未选列；表头及选择列表须唯一；未知列名或不齐整的行报错。 |
| `galaxy_add_line@1.0` | 添加首行或尾行 | 字面文本；已有行统一为 LF，避免末行无换行时与尾行粘连。 |
| `galaxy_recurring_lines@1.0` | 重复生成指定文本行 | 显式指定一个重复组；读完输入但不用输入行数推导次数；输出扩展最多 16 MiB 字符。 |
| `galaxy_remove_columns_by_header@1.0` | 按表头删除或保留列 | UTF-8 TSV；可清理首列表头的前缀字符，保持原始列序；未知列名报错。 |
| `galaxy_sort_columns_by_header@1.0` | 按表头字典顺序排列列 | 可将一个从 1 开始的列固定在首位；0 表示排序全部列；表头须唯一。 |
| `galaxy_add_input_name@1.0` | 添加来源标签列 | 显式标签，不依赖文件路径；可设置表头及前置或后置。 |
| `galaxy_fixed_width_to_tabular@1.0` | 定宽文本转表格 | 仅显式字符宽度，可为空缺尾部补空格；长行或未允许补齐的短行报错。不提供自动边界检测、FORTRAN 描述符、报告或编码转换。 |
| `galaxy_colorspace_to_fasta@1.0` | SOLiD 色彩空间转核苷酸 | A/C/G/T 引导碱基，后面只能是 0–3；可保留引导碱基，支持折行。单条序列最多 8 MiB 字符。 |
| `galaxy_split_paired_reads@1.0` | 提取拼接双端 FASTQ 的一端 | 选择 mate 1 或 2；校验四行记录、标题匹配、序列及质量长度相同且为偶数。空 + 标题保持为空，使输出 FASTQ 合法，不静默截断。 |
| `galaxy_pileup_interval@1.0` | 合并满足深度的相邻 pileup 位点 | 输入坐标从 1 开始，输出为从 0 开始的左闭右开区间；可配置列以支持六列、旧十列和自定义布局。活动区间最多 8 MiB 碱基；空行跳过而不中止全流。 |
| `galaxy_divide_pg_snp@1.0` | 拆分 pgSnp 等位基因列 | 超过两个等位基因的行跳过；纯合第二等位基因为 N 或指定参考列，频率/分数补零；附加列保留。 |
| `galaxy_vcf_to_pgsnp@1.0` | VCF SNP 基因型转 pgSnp 计数 | sample_column 为 0 时选全部样本，否则须 >=10；仅处理单碱基 A/C/G/T 且 FILTER 为 PASS 或缺失的 SNP。支持 GT 任意位置、单倍体、相位、所有等位索引；缺失等位忽略。 |
| `galaxy_reverse_complement@1.0` | FASTA 反向互补 | 仅 FASTA；DNA/RNA IUPAC 字母，保持大小写，拒绝错误字符或混合 T/U；单序列最多 8 MiB 字符。 |
| `galaxy_translate@1.0` | 核苷酸 FASTA 翻译为蛋白质 | 仅标准遗传密码表 1；完整模糊密码子输出 X；末尾不足三碱基报错。CDS 要求 ATG 起始、末尾终止且无内部终止，输出去掉末端终止符。可在首个终止处停止。 |
| `galaxy_codon_count@1.0` | 汇总 64 种密码子 | 仅完整 A/C/G/T 密码子、阅读框为 0；长度须为 3 的倍数；按固定字典序输出，包括零计数。 |
| `galaxy_base_distribution@1.0` | 统计各序列碱基组成 | FASTA；GC 分母包括模糊碱基，空序列 GC 为 0。沿用上游 CG% 列名，但数值是六位小数的比例，不是百分数。 |
| `galaxy_find_subsequences@1.0` | 查找核苷酸模体 | FASTA 输入，输出 BED6 左闭右开区间；支持 IUPAC 和双链，反链使用反向互补以修正上游只互补的问题。统一包含重叠命中；每序列比较预算为序列长度×模体长度 <=64000000，每链最多 100000 命中。 |
| `galaxy_fasta_stats@1.0` | 汇总 scaffold、contig 与碱基统计 | 只输出统计 TSV，不另输出 gap BED。N 连续区段切分 contig，内部 N 段计为 gap；N50 使用 >= 阈值，修正上游严格大于的边界。最多 100000 条序列及 contig，单序列最多 8 MiB 字符；可指定 genome_size 计算 NG50。 |
| `galaxy_motif_flank_counts@1.0` | 统计 indel 两侧含模体的窗口 | 输入为奇数个制表分隔窗口，中央须为 indel。模体配置为 sequence 或 name=sequence，按不区分大小写的 DNA 字面串匹配，不支持正则。按输入行、模体次序输出以保持流式；上游为模体优先次序。 |

## 开发、验证和运行

```bash
cd examples/operators/galaxy
python3 extend-catalog.py
python3 make-catalog.py
sbt package 'Test/runMain xstream.galaxy.ExtendedSafetyCheck'
python3 verify-extended.py --only reverse_complement --report verification-reverse-complement.json
cd ../../..
xstream-engine/target/universal/stage/bin/xstream-runner examples/operators/galaxy/engine.yaml examples/operators/galaxy/jobs-extended/reverse_complement.yaml
```

Python 需安装 PyArrow 和 PyYAML，并可通过 Docker 使用配置的共享 JVM 镜像。每个 `jobs-extended/<name>.yaml` 均配有 `data/extended/<name>.txt`。完整示例包含读取输入，仓库详情页只展示当前算子的步骤。参数、默认值、完整示例及回归预期在 `extra-specs.json` 中；`extend-catalog.py` 可重新生成契约、输入与示例 Job。全流排序、反转及逐条序列处理不宣称零拷贝或完全不缓存。

## 来源与审核

已审查的原始源码、XML、样例及许可证保留在 `upstream-extended/{devteam,community,iuc}/`。`upstream-extended-manifest.json` 记录仓库地址、固定版本及 SHA-256。对于依赖可执行表达式、外部程序、重放或附加文件的上游功能，适配器只公开上表声明的子集，未支持选项会被拒绝；这些包不保证替代所有上游命令行功能。仓库仅接收源码，在 `jvm-arrow@2.1` 上构建 Scala 薄 JAR，使用分块 Arrow IPC 及结构推导/预览测试该产物后再审批。管理员可信度属于评估，不是安全保证。

## 组合并核验已发布工具

[序列流水线](../examples/operators/galaxy/job-extended-pipeline.yaml)解码 SOLiD 色彩空间序列，计算反向互补，统计序列长度并附加明确的输入标签。输出为 `seq1\t4\trun1\n`。

```mermaid
flowchart LR
    I[Item bytes: A013] --> D[色彩空间解码: AACG]
    D --> R[反向互补: CGTT]
    R --> L[FASTA 长度: 4]
    L --> N[附加标签: run1]
    N --> O[TSV bytes 流]
```

发布后从 XStream 项目根目录运行：

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine-extended.yaml \
  examples/operators/galaxy/job-extended-pipeline.yaml
python3 examples/operators/galaxy/verify-extended-jobs.py
python3 examples/operators/galaxy/audit-publication.py --url http://127.0.0.1:8820
```

[公开目录核验](../examples/operators/galaxy/verification-public-catalog.json)检查 50 个不同名称、确切版本、完整端口及参数说明、使用示例、匿名可下载的源码与产物哈希、入口类及内嵌许可声明。已记录的核验还将每个产物与其通过的仓库构建测试报告进行匹配。[扩展 Job 验证](../examples/operators/galaxy/verification-extended-jobs.json)将 30 个新增 Job 的输出与独立指定的测试期望逐项比较。[流水线验证](../examples/operators/galaxy/verification-extended-pipeline.json)覆盖与原有 FASTA 长度算子的组合。[安全测试报告](../examples/operators/galaxy/verification-safety.json)记录四组安全测试全部通过；`ExtendedSafetyCheck` 检查 249 个用例，包括取消、I/O 异常、非法 UTF-8、行长度限制、上游标准测试及非法生物信息输入。管理员的可信度评分不能代替这些支持范围及资源限制。
