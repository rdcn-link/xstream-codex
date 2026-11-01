# XStream 引擎

以独立或嵌入方式执行流式 YAML Job 的 Scala 引擎。一个 JVM 共享一套 **DataCatalog → Dataset → Item**。嵌入应用直接共享自身 DataCatalog；本地执行无需启动服务器。

需要 JDK 17 和 sbt。从仓库根目录执行：

```bash
cd xstream-engine
sbt test stage
cd ..
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/xstream/engine.yaml examples/xstream/read.yaml
```

`xstream-runner` 是本模块发布的程序，不是独立模块。记录输出为 JSONL，字节流直接写 stdout。启动参数为 `CONFIG.yaml JOB.yaml [VARIABLES.yaml] [POLICY.yaml]`。

`read_item.params.uri: context:/dataset/item` 引用已绑定的 DataCatalog，也可以使用显式的 `dacp://host:port/dataset/item` URI。远程连接和凭据配置在 `nodes`。算子来自审核公开的仓库，或显式引用[本地源码目录](../docs/local-operators_zh.md)，保留现有隔离运行环境及兼容算子同进程合并。

Scala 嵌入、插件注册、取消、物化/重放、目录生命周期及设计器配置见[完整指南](../docs/xstream_zh.md)。[English](README.md)。

## 组成与边界

- `xstream-engine`：可嵌入的执行引擎及 `xstream-runner` 启动程序。
- [xstream-designer](../xstream-designer/README_zh.md)：可视化编写 YAML Job，浏览本地和远程目录，推导结构并提供有大小限制的样例。

本地执行直接读取 JVM 目录中的 Item。显式远程 URI 使用配置的客户端连接；独立运行程序通过可选执行目标插件和显式放置策略支持远程计算。流连接无需中间文件。物化和重放由执行策略显式指定；算子包、运行环境及执行历史仍可能使用磁盘。

示例配置的算子仓库地址为 `http://localhost:8820`；其中内置 `read_item` 步骤无需从仓库下载。使用仓库算子的作业需要该仓库可用或已有经过验证的本地缓存。请从仓库根目录运行启动程序，确保相对数据及存储路径解析正确。启动程序要求 Job 恰好选择一个输出，并将该流写入 stdout。嵌入式 API 支持多个输出。

另见[配置示例](../examples/xstream/engine.yaml)、[Job 示例](../examples/xstream/read.yaml)和 [Job 语法](../docs/jobs_zh.md)。


执行历史记录引擎实际选择的执行单元。执行单元包含与固定镜像匹配的仓库运行环境型号、版本及依赖版本。同进程执行单元列出所属步骤及运行环境镜像；独立步骤和宿主流读取分别记录。展示端不得根据运行环境镜像相同推测步骤已融合。

[执行目标与物理规划](../docs/execution-targets_zh.md)。
