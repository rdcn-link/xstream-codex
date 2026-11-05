# XStream

<img src="assets/xstream-logo.svg" alt="XStream" width="96">

[English](README.md)

XStream 提供 Dataset 管理与 Job 执行，包含 Scala 数据 SDK、可嵌入的流式引擎和浏览器图形设计器。步骤消费和生成数据流，中间数据无需保存为文件；需要时可通过显式执行策略启用物化和重放。

**Scala 是新建 XStream 算子的首选开发语言。** 建议从 JVM SDK 模板开始，将数据处理、结构推导和有界样例放在同一个算子实现中。Java 使用相同的 JVM 契约；Python、脚本和原生命令继续用于已有程序及特定语言生态的库。

XStream 在本地执行 Job 的每个步骤。远程 `read_item` URI 为本地算子提供数据流。DACP 客户端负责向远程服务提交 Job，Union 负责成员之间的自动放置。

## 组成

| 模块 | 用途 |
|---|---|
| [xstream-data](xstream-data/README_zh.md) | DataCatalog → Dataset → Item、类型化 DataView/DataStream 契约、插件发现、文件及科学格式实现和网络编码 |
| [xstream-operator-sdk](xstream-operator-sdk/README_zh.md) | JVM/Python 算子模板及元数据契约 |
| [xstream-operator-inference](xstream-operator-inference/README_zh.md) | 按需启动并复用的结构及样例推导 worker |
| [xstream-operator-repository](https://github.com/rdcn-link/xstream-operator-repository/blob/main/docs/browsing_zh.md) | 源码提交、隔离构建、审核、公开算子和运行环境下载 |
| [xstream-engine](xstream-engine/README_zh.md) | 本地嵌入式执行、取消、执行策略及 `xstream-runner` 启动程序 |
| [xstream-designer](xstream-designer/README_zh.md) | 图形化作业编辑、目录、变量、结构和样例预览、聊天草案及 YAML 导入导出 |

`context:/dataset/item` 在当前 JVM 绑定的目录中解析；显式 `dacp://host:port/dataset/item` URI 使用配置的远程连接。科学数组、图像和序列保留自身结构，不会被隐式展平成记录。格式和后端实现通过插件契约注册。

先运行[第一个 Job](docs/quickstart_zh.md)，再按[文档目录](docs/README_zh.md)查找数据管理、算子开发和嵌入 API。

## 构建

使用 JDK 17 和 sbt 1.10.x。构建只需要本仓库，不需要 DACP 源码或 DACP 制品。初始化脚本按依赖顺序将 XStream 开发制品发布到本机仓库。

```bash
git clone git@github.com:rdcn-link/xstream-codex.git
cd xstream-codex
scripts/bootstrap-local.sh
sbt test
(cd xstream-engine && sbt stage)
(cd xstream-designer && sbt stage)
```

使用 DACP URI 读取远程 Item，使用 `context:` 访问已配置的本地目录。详见[本地执行与远程数据](docs/execution-targets_zh.md)。

## 运行

在 XStream 项目根目录执行：

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/xstream/engine.yaml examples/xstream/read.yaml
xstream-designer/target/universal/stage/bin/xstream-designer \
  xstream-designer/example.yaml
```

请先修改配置路径、服务地址和凭据。启动程序将所选输出流写入 stdout；设计器默认监听 `http://127.0.0.1:8840/`。算子使用审核公开的仓库、经校验的包缓存，或用于独立开发的[本地源码库](docs/local-operators_zh.md)，内置 `read_item` 无需下载算子包。

## 文档

- [编写算子](docs/operator-authoring_zh.md)
- [源码提交与审核](docs/operator-submission_zh.md)
- [引擎和嵌入](docs/xstream_zh.md)
- [类型化流和科学视图](docs/data-streams_zh.md)
- [Arrow 流契约与数据表示](docs/arrow-streams_zh.md)
- [科学数据编排和预览](docs/designer-scientific_zh.md)
- [插件契约和注册](xstream-data/README_zh.md)
- [作业 YAML](docs/jobs_zh.md)

## 标志

矢量[完整标志](assets/xstream-logo.svg)和[图形符号](assets/xstream-mark.svg)以交叉的数据流构成 X。设计器使用图形符号作为 favicon。


完整 Scala 示例：[DataView 与视图流](docs/data-view-examples_zh.md)。

## 架构

本地目录、引擎和设计器可独立使用，远程服务通过可选连接器接入。

```mermaid
flowchart LR
  Designer["可视化设计器"] --> Job["Job YAML 与策略"]
  Runner["Runner / 嵌入宿主"] --> Engine["XStream 引擎"]
  Job --> Engine
  Catalog["DataCatalog"] --> View["Item / DataView"]
  View --> Arrow["Arrow 流"] --> Engine
  Repository["算子仓库"] --> Engine
  Engine --> Result["结果流"]
  Remote["可选远程连接器"] -.-> View
```

## 视觉样式

标准视觉资源为 `assets/xstream-mark.svg` 和 `assets/xstream-logo.svg`。X 图形背景包含五条平行、连续、短波长且低波幅的小波浪线。各应用应保持图标尺寸、深色底色和青蓝紫橙配色一致。

依赖声明、锁定环境、执行单元和二进制包装：[依赖环境指南](docs/dependency-environments_zh.md).

## 性能测试

[混合语言 Airflow、模型工具与 XStream 测试](docs/orchestration-llm-benchmark_zh.md)。[测试程序与复现方法](benchmarks/orchestration/README_zh.md)。
