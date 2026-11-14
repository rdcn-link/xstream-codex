# 仓库流端点算子

[English](README.md)

`item_source@1.0` 和 `client_sink@1.0` 是具有版本的仓库算子。Job 从配置的仓库或已验证缓存解析公开审核通过的清单及通过摘要校验的程序。Designer 展示仓库中的元信息、ICON、角色、实现类型和可信度。没有隐式注册或程序包缺失时的内置回退。

| 算子 | 角色 | 程序操作 |
|---|---|---|
| `item_source@1.0` | Source | `catalog.read`：打开目录 Item 的指定 View |
| `client_sink@1.0` | Sink | `client.receive`：将具名流绑定至 Job 调用方 |

这些 SDK 传输程序使用 `stream` 执行器。程序是包含 `api_version: 1` 和 `operation` 的 JSON 文档，选择受控的宿主传输服务；不能在宿主 JVM 执行程序包代码、选择宿主命令或绕过访问控制。目录授权、命名空间校验、取消及流清理由宿主负责。`context:` 指向宿主绑定的 DataCatalog。调用方接收端支持多个具名结果绑定，不发布数据集。两个端点都是图边界，不创建算子容器。

`StreamExecutor` 通过 `META-INF/services/xstream.execution.OperatorExecutor` 注册；仓库的 `StreamBuildRecipe` 使用 `META-INF/services/xstream.operator.SourceBuildRecipe`。`stream` 构建语言在隔离构建容器中复制 `endpoint.json`，发布前校验字段、大小、版本和操作。引擎及 Designer 安装 `xstream-plugin-operator-executors`，仓库安装 `xstream-plugin-source-analysis`。

提交两个程序，也可同时执行管理员审批：

```bash
python3 publish.py --url http://127.0.0.1:8820 \
  --token-file /secure/submitter.token \
  --approve-password-file /secure/admin-password
```

指定管理员选项后，若不存在 `stream-endpoints@1.0`，脚本使用已注册的 Scala 构建镜像创建该环境，在隔离环境中执行复制。由管理员另行注册环境和审核时，省略此选项。程序包不包含凭据。参见[算子开发](../../../docs/operator-authoring_zh.md)、[Job YAML](../../../docs/jobs_zh.md)及 [DataView 示例](../../../docs/data-view-examples_zh.md)。
