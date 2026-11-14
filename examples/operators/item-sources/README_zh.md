# Item 集合算子

[English](README.md)

提供 `folder_items_source`、`csv_items_source`、`item_inventory`、`item_byte_count` 的 Scala SDK 实现。Source 输出资源引用，不输出文件内容；通过执行宿主的私有 Item 访问服务读取，需要运行镜像安装匹配版本的 SDK、execution API 与 data-core。

| 算子 | 角色 | 输入 | 输出 |
|---|---|---|---|
| folder_items_source | source | URI 参数、递归选项 | items 集合 |
| csv_items_source | source | CSV URI 参数、uri_column | items 集合 |
| item_inventory | transform | items | 包含 URI、kind、format 的文本 bytes |
| item_byte_count | transform | items | 逐成员读取 bytes，输出 URI 和字节数 |


## 构建与测试

```bash
cd examples/operators/item-sources
sbt test package
```

构建环境需要从配置的制品仓库取得 JVM SDK。`*.json` 展示执行及端口声明；提交算子仓库时还需补齐运行时固定引用、源码来源、图标、参数填写说明/示例和完整使用文档。输出端口由算子方法推导，不在 manifest 顶层声明。见[算子提交](../../../docs/operator-submission_zh.md)。

## 本地使用

在引擎的 `inline_runtimes.jvm_tools` 中配置已安装且 SDK 版本匹配的 JVM 镜像。将以下定义放入本地 Job，步骤使用 `operator: {inline: folder_items}` 引用：

```yaml
operators:
  folder_items:
    type: sdk
    role: source
    runtime: jvm_tools
    program:
      path: ./target/scala-2.13/xstream-item-sources_2.13-1.0.jar
      entrypoint: xstream.examples.items.FolderItemsSource
    inputs: {}
```

步骤参数为 `{uri: 'context:/science/labels', recursive: true}`。CSV Source 使用 `CsvItemsSource`，参数为 `{uri: 'context:/science/manifest.csv', uri_column: uri}`。CSV 清单保留行顺序、重复项和其他列的元数据；成员信息允许在读取前未知。相对路径以清单的逻辑父目录为基准，不接受原始文件系统路径和携带凭据的 URI。

集合处理算子声明 `representations: [items]`，使用 `ctx.input("source").foreachItem` 遍历。需要内容时使用 `ctx.openItem(item, "bytes") { stream => ... }`，在回调内增量消费并释放资源。见 [Item 引用流](../../../docs/items-stream_zh.md)。远程 Job 只接受公开审核通过的仓库引用；内联定义仅用于本地。

单个 Item 使用 `item_source` 的 `view` 参数直接读取内容；集合 Source 输出引用，由集合处理算子显式打开成员。

## 发布

先注册包含匹配 SDK 的 `jvm-items@1.0` 运行时，再执行：

```bash
python3 publish.py --url http://localhost:8820 \
  --token-file /path/to/submitter.token \
  --approve-password-file /path/to/admin-password
```
省略审批密码参数则等待管理员审核。`item_byte_count` 跳过不提供 bytes 的目录，保留重复项和顺序；读取失败会使 Job 失败。

## 端到端验证

构建匹配的镜像与 runner 后运行：

```bash
python3 scripts/test-items-stream.py --image xstream/operator-jvm-items:1.0
```
从项目根目录执行，需要 Docker 访问权限和已发布的 `client_sink@1.0`。测试在隔离容器中验证 ZIP 目录、CSV 重复项、按需读取、缺失项失败及路径穿越拒绝。
