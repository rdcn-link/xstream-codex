# 本地执行与远程数据

XStream 在本地引擎中执行 Job 的每个步骤。`context:/dataset/item` 引用宿主 JVM 的 DataCatalog。`read_item` 使用远程 `dacp:` URI 时，以流读取该 Item；后续算子仍在本地执行。远程读取权限不代表提交 Job 的权限。

```mermaid
flowchart LR
  R[远程 Item 服务] -->|Item 流| Read[本地 read_item]
  Read --> Process[本地算子] --> Result[结果流]
```

## 远程 Item 插件

`RemoteItemProvider` 和 `RemoteItemRequest` 位于 `xstream.execution`。将可选的 `xstream-dacp-client` 连接器、`xstream-dacp-protocol` 及运行依赖安装到宿主类路径或 `plugin_directory`。普通 Runner 发行包不打包该连接器。不要重复安装宿主已提供的 XStream API JAR。

```yaml
nodes:
  - url: dacp://node-a.example.org:18861
    ca: /etc/dacp/ca.crt
    token_file: /etc/dacp/node-a.token
```

插件实现 `RemoteItemProvider`，校验自身配置，并在 `META-INF/services/xstream.execution.RemoteItemProvider` 注册具有公开无参构造器的实现类。DACP 实现为 `xstream.connector.dacp.DacpRemoteItemProvider`。`supports(uri)` 选择适配器；`open` 返回由调用者关闭的 `FrameSource`。失败时释放已打开的资源，遵守取消信号并限制帧大小。PluginEnvironment 负责发现和关闭提供者。

## 执行职责

| 组件 | 职责 |
| --- | --- |
| XStream | 本地执行单元、流处理、同进程合并、取消、超时、重试和物化/重放 |
| DACP 客户端 | 连接远程 DACP 服务器，提交、消费、取消或重放 Job |
| DACP 服务器 | 授权请求，在内嵌的本地 XStream 引擎执行接受的 Job，并共享 DataCatalog |
| Union | 解析公开数据集、选择成员放置位置并下发子 Job；自动计算下推仅由 Union 提供 |
| Designer | 编辑逻辑 Job、浏览选定上下文、推导结构/样例并导出 YAML |

显式远程执行使用 DACP SDK 或 Shell 连接节点 A，将完整 Job 提交给 A，此时 `context:` 指向 A 的目录。本地 XStream 执行策略包含 `materialization`、`max_retries` 和 `timeout_seconds`。

## Designer Target

Target 默认为 **Local**，可选择配置的 `nodes`。选择 A 后，**In-context catalog** 展示 A 的目录，其他目录入口不重复显示 A。在该上下文选择 Item 生成 `context:/dataset/item`；其他目录入口生成完整 DACP URI。Target 属于当前浏览器会话，不执行或提交 Job，也不是导出 Job YAML 中的放置字段。使用 DACP 将 YAML 提交给所选服务。普通结构推导 worker 在 Designer 服务器本地运行，上下文的元信息和样例来自所选目标。

测试覆盖本地执行、远程读取后继续本地处理、拒绝 placement 策略、Target 选择和凭据隔离。运行 `(cd xstream-engine && sbt test)`、`(cd xstream-designer && sbt test)` 和 `(cd connectors/xstream-dacp-client && sbt test)`。

切换 Target 需要打开修改窗口并明确确认。Job 可将 `designer.target` 保存为 `local` 或 DACP 服务源地址。加载设计目标时会根据配置节点提示确认；不会访问不可用的目标。该字段属于执行时忽略的编排元信息。参见[Designer Target](../xstream-designer/README_zh.md#确认及记住-target)。
