# DACP 与 XStream 的项目边界

XStream 负责数据模型、Arrow 流、Job 语言、执行策略、结构推导、本地执行、执行单元与物化。DACP 负责远程服务、授权、S3 和 Union 成员/命名空间管理。

`RemoteItemProvider` 为本地步骤提供远程数据流。DACP 客户端将 Job 提交给远程服务，服务内嵌本地 XStream 引擎。Union 负责自动成员放置和下发。Designer 选择编排上下文并编辑 Job，不规划或展示执行。

XStream 独立构建不读取 DACP 源码，也不要求运行 DACP 服务。DACP 消费版本化 XStream 制品。官网通过文档快照独立构建，跨项目内容链接到其权威文档。

元数据协议以 DACP 的 `proto/dacp.proto` 为权威，`scripts/export-xstream-protocol.py` 导出或校验连接器快照。该命令是显式集成工具，不是 XStream 构建依赖。

引擎使用 `xstream.engine`，执行契约使用 `xstream.execution`，数据插件使用各自的 `xstream.data.*` 包。服务注册文件和宿主调用方使用相同的包名。DACP 部分集成测试通过显式的 `XSTREAM_SOURCE_ROOT` 使用 XStream 示例；生产启动不读取该目录。

详见[本地执行与远程数据](execution-targets_zh.md)。验证：`python3 scripts/check-independence.py`、`python3 scripts/check-doc-translations.py`，以及引擎和连接器的 `sbt test`。
