# 常见问题

[English](faq.md)

## 1. Job Designer 的 Target 列表如何设置？

Designer 固定提供 **Local**。管理员通过 Designer 启动 YAML 中的 `nodes` 增加远程 Target；目标可以是普通 DACP 节点，也可以是 Union 服务。

```yaml
local_catalog:
  datasets:
    weather:
      provider: directory
      source:
        root: /srv/weather

nodes:
  - url: dacp://union.example.org:19860
    ca: /etc/xstream/ca.crt
    token_file: /etc/xstream/union.token
  - url: dacp://node-a.example.org:18861
    ca: /etc/xstream/ca.crt
    token_file: /etc/xstream/node-a.token
```

将地址和路径替换为部署中的实际值。`local_catalog` 提供 Designer 本地数据目录，`nodes` 提供可选的远程连接。CA 和 Token 文件由 Designer 服务端读取，不发送给浏览器。`token_file` 中只保存 Token 值，不保存完整的凭据 JSON 响应。上游账号须具有结构推导和样例请求所需权限；能查看公开元数据并不表示具有执行权限。

修改启动 Designer 时传入的配置文件，重启 Designer，然后刷新浏览器：

```bash
xstream-designer /etc/xstream/designer.yaml
```

访客通过 **Target: … → Change… → Use selected target** 选择管理员预先配置的目标，不能在界面中随意输入服务器地址或凭据。

选择 Target 后，**Target catalog** 和结构、输出样例推导使用的上下文随之变化。从该目录选择的 Item 使用 `context:/dataset/item`；从其他目录选择的 Item 使用完整远程 URI。已选 Target 不再重复出现在其他目录根节点中。切换 Target 后须检查上下文 Item 路径，因为同一路径在不同目标上可能对应不同数据。

导出 YAML 中可选的 `designer.target` 保存设计上下文，不会提交 Job、决定执行路由或授予权限。Job 提交是独立操作，实际执行目标的数据目录须与 Job 中的 `context:` 引用对应。

参见[设计器指南](designer-guide_zh.md)和 [Designer 配置参考](../xstream-designer/README_zh.md)。

## 2. 结构推导可以匿名访问吗？

Designer 网页本身无需用户登录，本地 Target 的推导由 Designer 服务端本地执行。远程 DACP 节点或 Union 的结构推导接口要求 `job:execute` 权限，普通账号通常需要 `runner` 角色。Designer 通过 `nodes` 中配置的 `token_file` 代表访客访问上游，访客无需输入 Token，Token 也不会传给浏览器。

因此，网页访客无需登录不等于远程服务允许匿名推导。公开目录与元数据可以匿名读取，结构推导和输出样例可能运行算子代码、读取数据并占用资源，遵循远程服务的权限检查。

## 3. 如何在 XStream Job 中读取本地文件？`context:` 指向哪里？

先通过引擎的 DataCatalog 发布目录，再使用 `read_item@1.0` 读取其中的 Item。例如，将以下引擎配置保存为 `engine.yaml`：

```yaml
datasets:
  local:
    provider: directory
    source:
      root: /data
```

将以下 Job 保存为 `read-local.yaml`，以记录形式读取 `/data/input.csv`：

```yaml
name: Read local CSV
steps:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/local/input.csv
      view: records
outputs:
  result: read
```

使用已安装的启动命令执行：

```bash
xstream-runner engine.yaml read-local.yaml
```

`context:` 指向执行该 Job 的引擎所绑定的 DataCatalog。这里的 `local` 是 Dataset 名称，`/input.csv` 是该 Dataset 内的 Item 路径。引擎进程必须具有读取 `/data/input.csv` 的权限。相对文件系统根目录按照进程工作目录解析。

同一份 Job 在远程服务器执行时，`context:` 指向该服务器的数据目录，而非提交者电脑上的文件。在 Designer 中，Local 指 Designer 服务器的数据目录，而非浏览器访客的文件系统。Designer 的本地目录配置位于 `local_catalog.datasets` 下，独立 runner 则使用上述顶层 `datasets` 配置。

如需读取原始文件字节，将 `view` 设置为 `bytes`。`records` 视图需要支持记录读取的格式插件，例如 CSV。`read_item` 接收数据目录中的 Item 地址，不直接接收 `/data/input.csv` 或 `file:///data/input.csv` 等任意文件系统路径。发布目录使其中的文件可以通过数据目录访问，不会自动将该主机目录挂载到算子容器内；算子通过输入端口接收相连的数据流。

参见[快速入门](quickstart_zh.md)、[数据模型](data-model_zh.md)和[本地执行指南](xstream_zh.md)。

## 4. 与 Airflow 等工作流引擎相比，XStream 更适合哪些场景？

XStream 是面向数据流的工作流执行引擎，尤其适合以数据处理为主体、希望尽早获得结果，或者需要将执行能力嵌入应用的场景。

| 场景 | XStream 提供的能力 |
|---|---|
| 交互式科学分析 | 带参数的 Job、图形化编制、结构推导和有大小限制的输出预览 |
| 多步骤数据转换 | 解析、筛选、转换、聚合之间通过流连接，减少中间文件交接 |
| 跨语言算法组合 | 通过具名端口和统一的 Arrow 数据契约连接 Scala、Java、Python 等受支持执行器 |
| 复杂文件与科学数据处理 | 通过 Item/View 访问普通文件、归档成员和结构化科学数据 |
| 应用内嵌分析 | JVM 应用可以直接嵌入本地引擎 |
| 可复用的分析服务 | 将避免修改外部业务状态的算子组合成带参数 Job |

典型处理链路如下：

```mermaid
flowchart LR
  A[读取观测数据] --> B[解析]
  B --> C[质量筛选]
  C --> D[分析计算]
  D --> E[结果流]
```

当算子支持增量处理时，下游可以在上游输出完成前开始计算；兼容步骤也可以共享执行单元。排序、某些 Join 和全局聚合仍可能缓存大量数据；流传递和进程合并并不保证每种工作负载都加速。

Airflow 尤其适合协调外部系统的调度工作流，例如定期抽取、等待上游服务、更新数据库、触发其他平台、历史补跑和发送通知，其优势包括调度、系统集成与运维工具。Airflow 不要求通过文件交换数据：XCom 面向少量值，大数据可以由任务通过外部存储或计算系统处理。参见官方的 [Airflow 介绍](https://airflow.apache.org/docs/apache-airflow/stable/)、[XCom 文档](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/xcoms.html)和[历史补跑文档](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/backfill.html)。

因此，XStream 适合作为科学数据分析、交互式分析应用以及可嵌入的跨语言数据流水线的基础。引擎在本地执行步骤，远程 Item 访问通过数据连接器实现，DACP/Union 提供远程 Job 调用与跨节点协作。支持时间窗口和持久状态恢复的持续流处理还需要进一步建设。

两者可以配合使用：Airflow 负责安排和管理分析 Job 的执行，XStream 负责其内部的数据处理流水线。实际性能及适用边界参见[工作流性能对比](orchestration-llm-benchmark_zh.md)。
