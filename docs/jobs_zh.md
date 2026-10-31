# 流式作业

[English](jobs.md)

DACP Server 与 Union 使用同一种 Job 格式。步骤通过 `inputs` 引用其他步骤的输出流建立关联，列表顺序不代表执行顺序。不支持 `depends_on` 控制依赖。

```yaml
name: weather-filter
tasks:
  - id: read
    title: Read weather observations
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/weather/observations
      view: records
  - id: copy
    title: Filter valid observations
    operator: piflow_filter@1.0
    inputs:
      source: read
    params:
      condition: "quality = '0'"
outputs:
  result: copy
```

## 字段与流连接

`name` 是必填的可读工作流名称，与自动生成的执行 ID 分开；最多 256 个字符，不允许空白名称或控制字符。每个 task 包含唯一 `id`、可选说明 `title`、带版本的 `operator`、命名输入 `inputs`，以及可选的参数 `params`。输入值和 Job 输出使用 `task` 或 `task:port`，省略端口时使用 `result`。不支持对象形式连接或连接级 `view`。每个输出最多有一个消费者；分发数据需要显式复制算子或不同输出端口。

`read_item@1.0` 是无需算子容器的内置传输源，无输入，输出端口为 `result`。只提供 `uri` 参数：`context:/dataset/item` 指向接收 Job 的 JVM 的 DataCatalog，`dacp://host:port/dataset/item` 显式指定服务。可选 `view` 选择已声明的表示，包括 `bytes`、`records`、`array`、`image`、`sequence`；未指定时按 records、bytes、首个可用科学视图的顺序选择。科学读取可通过 `selection` 映射指定范围。可选 `sql` 查询 records 视图，提供后默认选择 records，显式选择 bytes 则报错。

向 Union 提交时，使用其公开数据项路径。Union 负责解析数据所在成员、分配步骤并下发节点子 Job；用户仅描述逻辑数据流。

## 结构推导与无输出步骤

所有算子必须实现[结构推导](https://github.com/rdcn-link/xstream-codex/blob/main/docs/structure-inference_zh.md)，输出描述由该方法给出，不在清单中声明。无输出算子可以推导出空输出映射，并成功结束而不发送数据帧。Job 可设 `outputs: {}`，客户端等待整体完成而不接收数据。未选入最终结果的分支仍会执行。 提交和执行不自动调用推导方法；仅在编制时按需显式调用 `infer`。

上下游以有界缓冲并发运行。故障、取消、超时会终止本次尝试并释放资源。重试与物化通过独立的[执行策略 YAML](https://github.com/rdcn-link/dacp-codex/blob/main/docs/materialization_zh.md) 配置。重试可能重复外部副作用，业务算子需要提供适当的幂等机制。


当前协作示例已发布的算子、参数和完整输入输出示例见[算子库清单](https://github.com/rdcn-link/xstream-codex/blob/main/docs/operator-catalog_zh.md)。

