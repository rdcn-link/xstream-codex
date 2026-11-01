# XStream Designer 中的科学数据视图

设计器可浏览本地及配置的远程 DataCatalog。逐层展开 Dataset、文件容器及其子对象，即可选择逻辑 Item：NetCDF 变量、FITS HDU 或 FASTA 的 `/sequences`。能力图标区分 records、bytes、数组和序列。文件夹在原位置展开；其原始字节仍可通过显式填写 `read_item` 参数读取。

选择 Item 后会创建或更新无输入的 `read_item@1.0` 步骤。自动选择顺序为 records、bytes、首个声明的科学视图。可在 **Parameters → view** 中选择其他可用表示，显式选择不支持的视图会报错。选择表单来自已安装的视图编码插件，与文件格式独立；新增编码器可自行提供表单，无需修改设计器注册表。

## 选择范围与变量

勾选 **selection**，再启用各个字段，即可限制科学数据读取范围。省略 selection 表示完整读取。切换视图会清除旧视图的 selection。参数支持与其他 Job 参数一致的 `$VARIABLE` 变量，在 Variables 标签页提供值；导出保留变量及选择条件。

| 视图 | 选择字段 | 结构 |
|---|---|---|
| array | `origin`、`shape`、`stride`，均按声明的轴顺序；stride 默认 1 | 维度名称和长度、坐标引用、单位、数值编码及属性 |
| sequence | 可选 `ids`、`offset`、`length`；偏移按每条序列内的符号计数 | 字母表及属性；每块保留序列 ID、偏移和结束标记 |

```yaml
name: Read a climate slice
steps:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/science/climate/temperature
      view: array
      selection:
        origin: [0, 0]
        shape: [$TIME_COUNT, 180]
        stride: [1, 1]
outputs:
  result: read
```

变量 YAML 为 `TIME_COUNT: 4`。示例假定 `/science/climate/temperature` 是边界允许该选择的二维变量。FITS 图像可使用 `uri: context:/science/sky/hdu0`、`view: array` 和 `selection: {origin: [20, 10], shape: [32, 32], stride: [1, 1]}`；FASTA 可使用 `uri: context:/science/genome/sequences`、`view: sequence` 和 `selection: {ids: [chr1], offset: 100, length: 80}`。执行目录必须发布相应文件别名。显式远程来源使用 `dacp://host:port/dataset/item`。SQL 仅用于 records，不能与科学视图选择条件组合。

## 结构与样例

步骤框显示紧凑结构表格；右侧 **Outputs** 标签页显示可读结构、完整描述 JSON 和样例表格。数组样例的每行是一个数据块，包含坐标和数量受限的原始数值列表，不是展平后的 records 视图。序列样例包含 ID、偏移、符号及结束标记。`values_truncated` / `symbols_truncated` 标记块内内容被缩短；样例的 `truncated` 还表示达到行数/字节预算或提前终止。

推导按上下游依赖进行，仅更新变化步骤及其下游。修改来源 selection 属于参数修改，会重新推导；移动步骤或删除下游步骤不会使保留的上游结果失效。科学来源直接从类型化视图读取描述，不启动算子 worker。仅在请求样例时读取样例，默认每端口最多 10 行/16 KiB，最大 100 行/64 KiB，同时遵守原有响应总预算。达到预算或取消后关闭读取器；仅推导结构时在取得描述后立即停止。

## 执行与扩展

`read_item` 会将 selection 传递到本地和远程读取，包括 Union 成员节点读取。科学数据 Job 帧保留表示名及 `start.structure` 描述信息。数据载荷是普通 Arrow IPC：坐标和序列身份以有类型的列表示。它们可以连至 Outlet。下游算子必须显式支持对应表示，bytes/records 模板不会自动处理科学视图；设计器的兼容性校验会报告不匹配。Scala 输出事件通过 `arrow(allocator)` 读取 Arrow 数据，Python 事件提供 `batch`。

实现 `DataViewSupport`，在 `META-INF/services/xstream.data.DataViewSupport` 注册。`selectionSchema` 声明类型化表单，`validateSelection(selection, schema)` 按描述校验边界。`preview(batch, maxRows, maxValues)` 直接从 Arrow 列返回有界样例对象。必须遵守两个上限，避免读取完整对象；宿主执行最终字节预算。插件没有网络编码方法。格式专有依赖属于格式插件。格式支持范围及随机访问暂存限制见[数据流契约](data-streams_zh.md)与 [xstream-data](../xstream-data/README_zh.md)。

仓库提供了小型合成示例：`examples/data/scientific/climate.nc`、`sky.fits`、`genome.fa`。可加载 [scientific-selection.yaml](../examples/xstream/scientific-selection.yaml)，该示例假定 `local_files` Dataset 发布 `examples/data` 目录。
