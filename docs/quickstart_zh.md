# 运行第一个 Job

从读取本地 JSONL 文件开始，观察记录流。此示例无需数据服务器，也无需下载算子。

## 1. 构建运行器

使用 JDK 17 和 sbt 1.10.x。在仓库根目录执行：

```bash
git clone https://github.com/rdcn-link/xstream-codex.git
cd xstream-codex
scripts/bootstrap-local.sh
(cd xstream-engine && sbt stage)
```

## 2. 检查数据目录

[engine.yaml](../examples/xstream/engine.yaml) 将 Dataset `weather` 映射到 `examples/data`，示例文件是 `examples/data/weather-observations.jsonl`。文件系统相对路径以进程工作目录为基准。

```yaml
datasets:
  weather:
    provider: directory
    source:
      root: examples/data
```

## 3. 读取 Item

[read.yaml](../examples/xstream/read.yaml) 的内容：

```yaml
name: Local catalog read
steps:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/weather/weather-observations.jsonl
      view: records
outputs:
  result: read
```

`context:` 选择当前数据目录，`weather` 选择 Dataset，其余路径选择 Item。`outputs.result` 选择运行器打印的步骤输出。

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/xstream/engine.yaml examples/xstream/read.yaml
```

终端随数据到达逐行输出 JSONL 记录。找不到 Item 时，先检查是否在仓库根目录运行，以及是否修改了数据目录根路径。选择 bytes 视图时则输出原始字节。

## 按任务继续

- [可视化设计](../xstream-designer/README_zh.md)：加载此 Job、检查输出结构并导出 YAML。
- [编写 Job](jobs_zh.md)：连接步骤、传入变量。
- [管理数据](data-model_zh.md)：Dataset、目录、归档和视图；[读取示例](data-view-examples_zh.md)。
- [开发算子](operator-authoring_zh.md)：模板、参数、推导及本地测试。
- [嵌入引擎](xstream_zh.md)：Scala API、生命周期及宿主配置。
