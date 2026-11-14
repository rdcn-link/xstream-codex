# 编写、提交和使用算子

[English](operator-authoring.md)

算子由 XStream 管理，可由独立引擎或 DACP 节点执行。仓库接受源码及带适配源码的第三方二进制提交，经扫描、隔离构建及管理员审核后才公开算子。执行器能够运行某种包，并不意味着公共提交接口接受直接上传这种成品。分类和最终可信度由管理员指定。

```mermaid
flowchart LR
  Code["编写处理、结构推导和样例代码"] --> Test["本地测试"] --> Dependencies["声明依赖"] --> Submit["提交源码"] --> Review["构建与审核"] --> Publish["发布与使用"]
```

每次提交仓库还需在 `documentation` 中提供易读的端口说明、参数描述/填写说明/示例，以及完整的可复制 Job。参见[必填的使用说明](operator-submission_zh.md#必填的使用说明)。仓库接收新提交前会校验这些字段。

## 四个相互独立的选择

| 选择 | 可用值 | 决定什么 |
|---|---|---|
| 图角色 `role` | `source`、`transform`、`sink` | 步骤从哪里得到输入、向哪里输出 |
| 接入类型 `type` | `sdk`、`command`、`script` | 用 SDK 实现、适配命令，还是运行脚本 |
| `executor` | `jvm`、`python`、`command`、`container` | 由哪个已安装的启动器在 Runtime 中运行算子 |
| 定义位置 | 算子仓库、本地库、Job 的 `operators` | 定义如何分发和选用 |

`source` 没有输入端口并产生流；`transform` 消费并产生流；`sink` 消费流且没有输出端口。仓库算子 `item_source@1.0` 是 Source，`client_sink@1.0` 是面向调用方的 Sink。Source、Transform、Sink 是图角色，每种角色都可以选择合适的实现类型。在 Job 中，步骤可以引用已发布的 `name@version`、[本地算子库](local-operators_zh.md)或[内联定义](inline-operators_zh.md)。内联定义用于本地 Job；远程节点只接受公开审核通过的算子。[Scala Source 和 Sink 示例](../examples/operators/scala-endpoints/README_zh.md)提供完整代码及发布方法。


## 选择四种执行器

`executor` 决定如何启动算子，Runtime 决定其运行环境；四种执行器都在 Docker 容器中执行。

| executor | 开发者需要提供 | 适合场景 | 推导与样例 |
|---|---|---|---|
| `jvm` | Scala/Java 实现、入口类及构建声明；执行产物为 JAR | 新算子、JVM 库、类型化流处理 | SDK 方法或模板 |
| `python` | Python 实现及依赖声明；执行产物可为 `.py`、`.zip` 或 `.whl` | 科学计算与 Python 库 | SDK 方法或模板 |
| `command` | 程序、运行环境及 stdin/stdout 映射配置 | 已有命令行工具、二进制程序 | 配置推导 bytes 输出；通用适配器不执行程序生成样例 |
| `container` | 自带入口并实现算子协议的镜像 | 自定义语言或完整运行栈 | 镜像内入口实现协议 |

开发顺序是：确定端口和参数 → 选择 Runtime/executor → 编写实现或适配配置 → 验证处理、结构推导、样例和取消 → 按仓库支持的提交方式审核发布。以下 Scala 和 Python 示例展示业务代码；命令与容器示例展示执行定义，不能直接当作仓库源码提交清单。

## 首选语言：Scala

**Scala 是新建 XStream 算子的首选开发语言。** 建议从 JVM SDK 模板开始，将数据处理、结构推导和有界样例放在同一个算子实现中。Java 使用相同的 JVM 契约；Python、脚本和原生命令继续用于已有程序及特定语言生态的库。

Scala 适合表达类型化的流变换，并可与引擎共用 JVM 接口和库。使用 `BytesOperator`、`ByteStreamOperator`、`RecordsOperator` 等模板，避免在业务代码中重复编写协议和资源管理逻辑。依赖、Scala 和 JDK 版本应匹配选用的运行环境。

语言相同并不自动保证零拷贝或同进程执行；执行单元合并仍取决于依赖兼容性、算子能力和执行策略。参阅[同进程合并](operator-fusion_zh.md)与[依赖环境](dependency-environments_zh.md)。

## 选择接口与运行环境

每个算子声明两个独立属性。`role` 为 `source`（无输入）、`transform`（有输入和输出）或 `sink`（有输入、无输出）；`type` 为 `sdk`、`command` 或 `script`，表示程序的接入方式。算子仓库把两者动态叠加到算子 ICON 上，不修改原始图形。仓库算子 `item_source@1.0` 启动 Item 数据流，`client_sink@1.0` 显式将流返回调用方。

先在仓库 `/runtimes` 选择实际注册的运行环境及版本。执行和构建依赖必须匹配；JVM 环境需要提供 XStream SDK，Scala 构建环境还需要 Scala 编译器。算子包仅包含业务代码，公共依赖留在共享运行环境中。

Scala 和 Java 可实现 `Operator`，或使用 JVM 的 `BytesOperator`、`ByteStreamOperator`、`RecordsOperator` 模板；Python 可使用 `BytesOperator` 或 `RecordsOperator`。模板提供结构及有界样例推导，修改输出结构时应覆盖相应方法。底层接口必须实现处理、结构推导和样例输出。

## jvm：Scala / Java

在 `src/example/Identity.scala` 保存以下代码，它直接把 `InputStream` 传给 `OutputStream`，不缓存整个 Item。提交时使用 `language: scala`、`entrypoint: example.Identity` 和注册的 JVM 运行环境。Java 同样可以实现 JVM SDK 的 `Operator` 接口。

```scala
package example

import java.io.{InputStream, OutputStream}
import com.fasterxml.jackson.databind.JsonNode
import xstream.operator.ByteStreamOperator

object Identity extends ByteStreamOperator {
  override def transform(input: InputStream, output: OutputStream,
                         params: JsonNode): Unit = {
    input.transferTo(output)
  }
}
```

## python：Python SDK

安装 SDK，在 `src/upper_ascii.py` 保存以下代码。`abc\n` 会输出 `ABC\n`；`uppercase: false` 则原样输出。这是 ASCII 示例，任意字节块不保证 UTF-8 字符或业务记录边界。

```bash
python -m pip install ./xstream-operator-sdk/python
```

```python
from xstream_operator import BytesOperator

class UpperAscii(BytesOperator):
    def transform(self, chunk, params):
        return chunk.upper() if params.get('uppercase', True) else chunk

if __name__ == '__main__':
    UpperAscii().main()
```

## command：复用命令行程序

如果程序已经通过 stdin 读取字节、通过 stdout 输出字节，可以使用框架自带适配器，无需编写基础流协议代码。例如以下执行定义片段把输入转成大写：

```yaml
executor: command
entrypoint: /usr/bin/tr
executor_config:
  args: [a-z, A-Z]
  stdin: source
  stdout: {port: result, representation: bytes, format: text}
inputs:
  source: {representations: [bytes], formats: []}
```

将片段补入包含名称、版本、镜像、图标和参数契约的完整执行定义。镜像必须实际包含 `/usr/bin/tr`、Python 和 PyArrow；它们不会在执行时自动安装。输入 `abc` 应得到 `ABC`，stdout 只能输出结果，诊断写 stderr。

当前最多一个 bytes 输入和一个 bytes 输出；源或汇可分别设置 `stdin: null` 或 `stdout: null`。`args` 是固定字符串列表，不替换 Job 参数，也不经过 shell。records 转换、文件名参数、多输入输出、动态参数及特殊格式需要专用适配代码。框架根据 stdout 配置推导结构，但不运行任意命令生成样例；需要样例时提供专用适配。完整配置、独立可执行文件挂载及取消行为见[原生命令算子](native-commands_zh.md)。

**发布边界：** 通用执行适配器不等于纯配置二进制上传接口。当前仓库的第三方二进制提交要求适配源码；不要把上述片段直接交给 `submit`。需要公开发布时，按照[二进制适配提交](dependency-environments_zh.md)准备适配源码及来源材料。

## container：镜像自行提供算子入口

选择此方式时，镜像的 ENTRYPOINT 就是算子程序；执行定义使用 `executor: container`，不配置 `entrypoint` 或 `artifact`，也不接受 `executor_config`。例如可以在提供 SDK、JDK 和依赖的基础镜像上加入自己的 Scala 算子：

```dockerfile
FROM example/jvm-arrow:1
COPY operator.jar /opt/operator/operator.jar
ENTRYPOINT ["/opt/java/bin/java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true", "-cp", "/opt/operator/operator.jar:/opt/lib/*:/opt/xstream/jars/*", "example.Identity"]
```

基础镜像名是占位符，必须替换为实际具备所需依赖的镜像。该入口类可使用前文的 Scala SDK 实现；Docker 镜像本身不会把任意程序自动变成算子。

入口必须处理框架提供的执行及推导请求，读取 `/input` 下的输入通道和参数，返回合法输出及有界样例。SDK 可承担协议处理；自定义语言则需实现对应协议。stdout 用于协议，日志写 stderr。镜像应适应只读根文件系统、禁用网络和受限临时空间。参见[打包参考](operator-packaging_zh.md)、[结构推导](structure-inference_zh.md)和[流契约](operator-streams_zh.md)。

本地或管理员管理的执行定义可以使用此类镜像。公共开发者提交仍按仓库支持的源码或二进制适配流程审核，不能仅凭 `executor: container` 直接上传任意镜像。

## 验证与发布检查

四种方式都应验证：空输入、多批次输入、输出结构、参数变化、失败退出、取消和资源释放。SDK 实现还应验证样例预算；`command` 的通用样例限制见上文。使用本地算子库运行测试 Job，并确保目标机器具备匹配的 Runtime、执行器插件及依赖。`jvm`/`python` 的兼容步骤可合并执行；`command` 不会因依赖相同而自动变成同一进程。

```bash
xstream-operator check ./my-operator
xstream-operator build ./my-operator --config ./engine.yaml
xstream-operator submit ./my-operator \
  --url https://repo.example.org --token-file ./publisher.token
```

这些命令针对符合源码项目约定的目录；不要用它们替代镜像执行定义的验证。构建需要配置好的本地构建环境，提交需要仓库签发的 token，批准前不可公开下载。依赖和二进制提交详见[依赖环境](dependency-environments_zh.md)，本地 Job 示例见[本地算子库](local-operators_zh.md)。

## 输入、参数与输出

`inputs` 声明命名输入端口及表示；`parameters` 声明 JSON 参数类型、默认值和约束。实现 `inferOutputs`/`infer_outputs`，返回输出端口和结构。参数值来自 Job 的 `params`，例如 `uppercase: true`，而非输入数据流。

记录模板按 Arrow RecordBatch/VectorSchemaRoot 处理表格，保留字段类型；需要更改字段时实现 `output_schema`/`outputSchema`。通用上下文使用 `ctx.input(name)`、`ctx.records_output`/`ctx.recordsOutput` 或 bytes 对应方法。多输入按端口名词典序逐个完整消费；先声明所有输出，再写数据。

多输入多输出的完整例子见 [split_orders](../examples/operators/python/split_orders/1.0/split_orders.py)，其 `left` 和 `right` 输入接收 `id:int64, amount:float64`，按 `min_amount` 分到 `accepted`、`rejected` 两个输出。Job 中使用 `left: step_a:result`、`right: step_b:result` 关联上游输出；省略端口时使用默认端口。源算子的 `inputs: {}`；无输出的算子也允许存在。

## 准备源码提交

以下清单发布前文的 Scala `Identity`。目录包含 `submission.yaml`、`src/example/Identity.scala` 和 `LICENSE.txt`。选择支持 Scala 构建的仓库 Runtime（如 `jvm-arrow@2.1`），并声明真实许可证。Python 和 Java 使用相同的提交及审核流程，详见[源码提交指南](operator-submission_zh.md)。

```yaml
name: scala_identity
version: '1.0'
role: transform
type: sdk
description: Stream bytes unchanged
language: scala
entrypoint: example.Identity
runtime: jvm-arrow@2.1
source_public: true
icon:
  path: M4 19L12 5l8 14M7 14h10
  color: '#14866b'
inputs:
  source:
    representations: [bytes]
    formats: []
parameters:
  type: object
  properties: {}
  required: []
  additionalProperties: false
provenance:
  license: Apache-2.0
  license_file: LICENSE.txt
```
源码归档不能包含预构建 JAR、wheel、可执行文件、嵌套归档或符号链接。以下命令在算子项目打包，随后从 XStream 项目调用提交工具：

```bash
python -m zipfile -c sources.zip src
xstream-operator submit /path/to/submission.yaml \
  --source /path/to/sources.zip --url https://operators.example.org \
  --token-file /secure/submitter.token
```
Token 由仓库管理员分配。提交成功返回 submission ID，不意味着已发布。管理员查看扫描、构建状态和源码，指定分类与可信度后批准；只有批准版本可公开下载。`source_public` 决定是否同时公开源码，管理员始终可查看源码。完整 API、隔离边界和审核流程见[源码提交与审核](operator-submission_zh.md)。

## 在 Job 中使用

```yaml
name: Stream observations
steps:
- id: read
  operator: item_source@1.0
  inputs: {}
  params:
    uri: context:/science/observations.csv
    view: bytes
- id: identity
  operator: scala_identity@1.0
  inputs:
    source: read
- id: client_sink
  operator: client_sink@1.0
  inputs:
    result: identity
```

把 URI 替换为目标执行目录中的真实 Item。`context:` 使用当前执行服务绑定的目录；远程 URI 显式指定 DACP 地址。独立执行使用 `xstream-runner CONFIG.yaml JOB.yaml [VARIABLES.yaml] [POLICY.yaml]`。

## 启动参数与协议边界

启动器的等价调用为 `/opt/java/bin/java -cp '/operator/operator.jar:/opt/lib/*:/opt/xstream/jars/*' example.Identity operator /input /input/params.json`；`operator` 是固定模式，`/input` 包含框架生成的输入清单及流通道，`/input/params.json` 包含参数对象，例如此处的 `{}`。单输入单输出可使用标准流，多端口由框架安排 FIFO。业务程序使用 SDK，不自行维护通道。

stdout 保留给流协议，日志写 stderr。输入须消费到 EOS；成功才发出结束事件，异常不能伪造成功。bytes 输出被分块；单条记录批次不得超过协议上限，样例还受行数和字节数预算限制。不要把流全部读入内存，也不要为普通中间步骤创建持久文件。

进一步阅读：[SDK](../xstream-operator-sdk/README_zh.md) · [参数](operator-parameters_zh.md) · [结构推导](structure-inference_zh.md) · [流协议](operator-streams_zh.md)

## 本地开发

独立引擎支持源码目录引用、可复用构建和直接提交源码，详见[本地算子库](local-operators_zh.md)。其中 `LocalOperatorBuilder` 是已实现的构建扩展点，内置 `python` 与 `command` 提供者通过 ServiceLoader 注册。

## 开发主题参考

| 目标 | 示例与参考 |
|---|---|
| 配置开发工具 | [CLI](../xstream-operator-sdk/cli/README_zh.md)、[本地算子](local-operators_zh.md) |
| 选择依赖并减小包体积 | [依赖环境](dependency-environments_zh.md)、[打包参考](operator-packaging_zh.md)、[运行时](operator-runtimes_zh.md) |
| 定义端口和数据流 | [流契约](operator-streams_zh.md)、[SDK](../xstream-operator-sdk/README_zh.md) |
| 提供参数、结构和样例 | [参数类型](operator-parameters_zh.md)、[结构推导](structure-inference_zh.md) |
| 使用脚本或第三方程序 | [脚本示例](../examples/operators/scripts/README_zh.md)、[原生命令](native-commands_zh.md) |
| 准备公开发布 | [提交](operator-submission_zh.md)、[来源材料](operator-provenance_zh.md)、[图标](operator-presentation_zh.md) |

## Job 内联（inline）算子的编写

本地开发时，可直接在 Job 顶层 `operators` 中定义算子，步骤通过 `operator: {inline: name}` 引用。SDK 程序选择 `sdk`，第三方可执行工具选择 `command`，内嵌源码或本地脚本选择 `script`；运行容器通过引擎配置的 `inline_runtimes` 指定。

[查看完整编写教程：运行环境配置、SDK 入口类、命令参数、脚本源码及完整可运行 Job](inline-operators_zh.md)。

内联定义仅用于本地执行。向远程节点或 Union 提交时，需先发布自定义算子，再使用公开审核通过的仓库引用。

## Item 集合输入

`items` 与 bytes、records、array 是并列的流表示。参见 [Item 引用流](items-stream_zh.md)、[完整 Source 与 Transform 示例](../examples/operators/item-sources/README_zh.md)和 [FAQ 第 6 条](faq_zh.md#6-自定义算子如何处理-items-引用流)。
