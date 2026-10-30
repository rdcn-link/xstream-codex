# 编写、提交和使用算子

[English](operator-authoring.md)

算子由 XStream 管理，可由独立引擎或 DACP 节点执行。开发者仅提交源码；仓库完成扫描、隔离构建及管理员审核后才公开算子。分类和最终可信度由管理员指定。

## 选择接口与运行环境

先在仓库 `/runtimes` 选择实际注册的运行环境及版本。执行和构建依赖必须匹配；JVM 环境需要提供 XStream SDK，Scala 构建环境还需要 Scala 编译器。算子包仅包含业务代码，公共依赖留在共享运行环境中。

Python 可继承 `BytesOperator` 或 `RecordsOperator`；Java/Scala 可实现 `Operator`，或使用 `BytesOperator`、`ByteStreamOperator`、`RecordsOperator` 模板。模板提供结构及有界样例推导，修改输出结构时应覆盖相应方法。底层接口必须实现处理、结构推导和样例输出。

## Python

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

## Scala

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

## 输入、参数与输出

`inputs` 声明命名输入端口及表示；`parameters` 声明 JSON 参数类型、默认值和约束。不要在清单中声明 `outputs`，输出端口和结构由 `inferOutputs`/`infer_outputs` 返回。参数值来自 Job 的 `params`，例如 `uppercase: true`，而非输入数据流。

记录模板按 Arrow RecordBatch/VectorSchemaRoot 处理表格，保留字段类型；需要更改字段时实现 `output_schema`/`outputSchema`。通用上下文使用 `ctx.input(name)`、`ctx.records_output`/`ctx.recordsOutput` 或 bytes 对应方法。多输入按端口名词典序逐个完整消费；先声明所有输出，再写数据。

多输入多输出的完整例子见 [split_orders](../examples/operators/python/split_orders/1.0/split_orders.py)，其 `left` 和 `right` 输入接收 `id:int64, amount:float64`，按 `min_amount` 分到 `accepted`、`rejected` 两个输出。Job 中使用 `left: step_a:result`、`right: step_b:result` 关联上游输出；省略端口时使用默认端口。源算子的 `inputs: {}`；无输出的算子也允许存在。

## 准备源码提交

目录包含 `submission.yaml`、`src/upper_ascii.py` 和 `LICENSE.txt`。示例中的 `python-arrow@1.0` 需替换为仓库实际提供的运行环境。许可声明应与你的源码实际许可一致。

```yaml
name: upper_ascii
version: '1.0'
description: Uppercase an ASCII byte stream
language: python
entrypoint: upper_ascii
runtime: python-arrow@1.0
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
  properties:
    uppercase:
      type: boolean
      default: true
      description: Convert ASCII letters to uppercase
  required: []
  additionalProperties: false
provenance:
  license: Apache-2.0
  license_file: LICENSE.txt
```
源码归档不能包含预构建 JAR、wheel、可执行文件、嵌套归档或符号链接。以下命令在算子项目打包，随后从 XStream 项目调用提交工具：

```bash
python -m zipfile -c sources.zip src
python scripts/submit-operator.py /path/to/submission.yaml \
  --source /path/to/sources.zip --url https://operators.example.org \
  --token-file /secure/submitter.token
```
Token 由仓库管理员分配。提交成功返回 submission ID，不意味着已发布。管理员查看扫描、构建状态和源码，指定分类与可信度后批准；只有批准版本可公开下载。`source_public` 决定是否同时公开源码，管理员始终可查看源码。完整 API、隔离边界和审核流程见[源码提交与审核](operator-submission_zh.md)。

## 在 Job 中使用

```yaml
name: Uppercase observations
tasks:
- id: read
  operator: read_item@1.0
  inputs: {}
  params:
    uri: context:/science/observations.csv
    view: bytes
- id: upper
  operator: upper_ascii@1.0
  inputs:
    source: read
  params:
    uppercase: true
outputs:
  result: upper
```

把 URI 替换为目标执行目录中的真实 Item。`context:` 使用当前执行服务绑定的目录；远程 URI 显式指定 DACP 地址。在 DACP Shell 中执行 `submit workflow.yaml`，客户端逐步接收并打印结果流；独立执行使用 `xstream-runner CONFIG.yaml JOB.yaml [VARIABLES.yaml] [POLICY.yaml]`。

## 启动参数与协议边界

启动器的等价调用为 `/usr/local/bin/python /operator/upper_ascii.py operator /input /input/params.json`；`operator` 是固定模式，`/input` 包含框架生成的输入清单及流通道，`/input/params.json` 包含如 `{"uppercase":true}` 的参数对象。单输入单输出可使用标准流，多端口由框架安排 FIFO。业务程序使用 SDK，不自行维护通道。

stdout 保留给流协议，日志写 stderr。输入须消费到 EOS；成功才发出结束事件，异常不能伪造成功。bytes 输出被分块；单条记录批次不得超过协议上限，样例还受行数和字节数预算限制。不要把流全部读入内存，也不要为普通中间步骤创建持久文件。

进一步阅读：[SDK](../xstream-operator-sdk/README_zh.md) · [参数](operator-parameters_zh.md) · [结构推导](structure-inference_zh.md) · [流协议](operator-streams_zh.md)
