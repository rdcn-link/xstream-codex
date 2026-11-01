# XStream 执行 API

[English](README.md)

提供算子帧、流描述、结构推导和限量样例、算子启动命令、执行历史及物化的公共契约。JSON/YAML 工具位于 `xstream.util`。本模块依赖 XStream 数据契约、Jackson 和 SnakeYAML，不包含 Docker、服务器或远程客户端实现。

```bash
(cd xstream-execution-api && sbt publishLocal)
```

插件以 `Provided` 依赖 `io.github.rdcn-link:xstream-execution-api_2.13:0.1.0-SNAPSHOT`，由嵌入宿主提供 API。在对应 `META-INF/services/` 文件中注册 `dacp.OperatorExecutor`、`dacp.ExecutionStoreProvider` 或 `dacp.MaterializationStoreProvider`。实现负责参数验证和资源清理。参见[插件开发](../docs/plugins_zh.md)、[算子打包](../docs/operator-packaging_zh.md)和[物化](../docs/materialization_zh.md)。

目录、Dataset、Item 和数据视图接口属于 [xstream-data](../xstream-data/README_zh.md)。
