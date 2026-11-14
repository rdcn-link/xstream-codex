# Scala Source 和 Sink 示例

[English](README.md)

四个小算子展示图中的角色与实现类型相互独立。四者均采用 `type: sdk` 和 Scala JVM SDK，以有界分块处理 Arrow 支持的 bytes 流；不在服务器写文件，也不访问外部服务。
每个实现都使用基础 `Operator` 接口；发布清单声明 Source 或 Sink 角色及端口契约。专门的 `SourceOperator`、`SinkOperator` 接口是可选的角色标记。

| 算子 | 角色 | 行为 |
|---|---|---|
| `text_source@1.0` | Source | 将 UTF-8 `text` 输出 `repeat` 次，输出上限 8 MiB |
| `range_source@1.0` | Source | 从 `start` 开始输出 `count` 个有符号整数，每行一个，最多 100,000 个 |
| `contains_text_sink@1.0` | Sink | 消费 bytes；若找不到 `text` 则使 Job 失败，支持跨块匹配 |
| `min_lines_sink@1.0` | Sink | 消费 bytes；若行数低于 `minimum` 则使 Job 失败 |

Source 步骤没有输入；Sink 步骤消费输入且没有输出端口。例如：

```yaml
name: Check generated lines
steps:
- id: generate
  operator: range_source@1.0
  inputs: {}
  params: {start: 1, count: 100}
- id: check
  operator: min_lines_sink@1.0
  inputs: {source: generate}
  params: {minimum: 100}
```

需要将 Source 流返回给调用方时，将其连接到 `client_sink@1.0`，而不是校验型 Sink。这些示例不替代读取目录 Item 的 `item_source@1.0`，也不替代持久化数据产品的数据集发布功能。

在本目录运行 `sbt test` 验证契约。在**本地**算子仓库提交并审核发布：

```bash
python3 publish.py --url http://127.0.0.1:8820 \
  --token-file /secure/submitter.token \
  --approve-password-file /secure/admin-password
```

省略 `--approve-password-file` 则提交后等待管理员审核。脚本只打包 Scala 源码、`pom.xml`、文档和许可证；仓库构建轻量 JAR。需要先注册 `jvm-arrow@2.1` 运行时。参见[算子开发](../../../docs/operator-authoring_zh.md)和[源码提交](../../../docs/operator-submission_zh.md)。
