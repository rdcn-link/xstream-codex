# XStream execution API

[中文](README_zh.md)

Public contracts for operator frames, stream descriptors, structure inference and bounded samples, operator launch commands, execution history and materialization. JSON/YAML utilities live in `xstream.util`. This module depends on XStream data contracts, Jackson and SnakeYAML, without Docker, server or remote-client implementations.

```bash
(cd xstream-execution-api && sbt publishLocal)
```

Plugins compile against `io.github.rdcn-link:xstream-execution-api_2.13:0.1.0-SNAPSHOT` as `Provided`; the embedding host supplies the API. Register `xstream.execution.OperatorExecutor`, `xstream.execution.ExecutionStoreProvider` or `xstream.execution.MaterializationStoreProvider` in matching `META-INF/services/` files. Implementations own settings validation and resource cleanup. See [plugin development](../docs/plugins.md), [operator packaging](../docs/operator-packaging.md), and [materialization](../docs/materialization.md).

Catalog, Dataset, Item and data-view interfaces belong to [xstream-data](../xstream-data/README.md).
