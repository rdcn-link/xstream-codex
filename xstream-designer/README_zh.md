# XStream 设计器

[English](README.md)

`xstream-designer` 是独立的 Scala HTTP 模块，提供英文的浏览器图形化 Job 设计界面，无登录、无用户管理。复用普通 Scala SDK 访问节点或 Union，设计器不执行作业，不依赖服务器内部实现。

## 启动

需要 JDK 17、sbt 1.10.x。先复制并修改 [example.yaml](example.yaml)，将地址、CA 与 Token 文件设置为部署环境的实际值。凭据只保存在服务端配置中，不返回浏览器。

```bash
cd xstream-designer
sbt test stage
target/universal/stage/bin/xstream-designer /etc/dacp/designer.yaml
```

默认地址为 `http://127.0.0.1:8840/`。需要网络访问时设置 `host: 0.0.0.0`。服务配置由管理员维护，访问网页无需登录；配置的上游普通客户端凭据决定网页可读取的目录和可请求的推导能力。

发行包为 Target catalog 配备 `xstream-data-archives` 插件：展开 ZIP、TAR、TAR.GZ/TGZ、GZIP Item 时可浏览其子项。GZIP 展示一个解压后的子项；TAR.GZ 直接展示归档成员。嵌套压缩包不递归展开。Dataset 与 Item 行均采用紧凑间距。

## 设计流程

1. Target 默认为 **Local**，可选择配置的远程节点。Target catalog 随 Target 变化，选择 Item 生成 `context:` URI；其他目录入口不重复显示该节点。目录分支按需展开加载。Target 选择编排上下文，远程 Job 提交由 DACP 提供，自动放置由 Union 提供。
2. 自动从配置文件的 `repository` 读取 `/v1/operators`。可搜索算子名称和说明、选择版本、拖到画布。`read_item@1.0` 是内置数据源。
3. 打开左侧 **Catalogs** 标签页，展开目录时加载数据集，展开数据集和文件夹时加载 Item。点击 Item 后，旁边浮动显示各个可用视图和复制路径的操作图标，悬停或键盘聚焦显示用途。选择视图后，为选中或新建的读取步骤设置 URI 和显式视图。复制路径保留数据集名称（如 `/science/climate.nc/temperature`）。仅点击文件夹左侧箭头展开或收起；点击名称显示操作图标。Union 连接使用 Union 的公开 URI。
4. 拖动卡片调整位置，点击输出端口启动随鼠标移动的预览线，再点击输入端口建立流依赖；右键或 Esc 取消。在右侧修改标题和参数；支持删除步骤、移除连接、撤销、重做与自动排列。
5. 画布始终有且仅有一个 **Outlet**，可拖动位置，但不能删除或复制。点击算子输出端口，再点击 Outlet，即创建 Job 输出绑定；Outlet 最多连接一个步骤的一个输出端口，新连接替换原绑定。属性侧栏仅编辑步骤属性与参数；结果流名称可在 Job YAML 中修改。选中连到 Outlet 的线并按 Delete 可删除对应绑定，Outlet 本身始终保留。导入文件中的 Job 输出也连到同一个 Outlet。布局保存在浏览器草案中，导出时默认写入 `designer` 辅助区。允许 `outputs: {}`；每个生产者端口最多一个消费者。每个输出端口下方展示表示、格式和可确定的 Schema 字段；未知结构明确标注。
6. 在 **Variables** 中编辑或加载独立 YAML 变量文件。参数可使用 `$INPUT_URI` 等完整变量值，保留整数、布尔和复合结构的类型。变量值不嵌入模板导出文件，使用 **Export variables** 单独导出。
7. 参数、变量及连接变化后自动推导输出结构，参数编辑停顿 650 毫秒后触发，合法修改自动保存并同步到导出的作业；未完成或非法输入保留在表单中并提示。设计器使用公共 `xstream-operator-inference` 模块，在自身环境中按需启动并复用推导 worker，从配置的算子仓库获取代码；普通算子的推导不经 Union 或成员节点。`read_item` 从本地目录或已配置服务查询 Item 元信息，上游变化也会更新下游。推导请求串行合并，过期响应丢弃。在右侧 **Outputs** 标签页查看当前步骤各输出端口的完整结构及有界样例。推导不运行 Job；结构返回后再请求有界样例，`read_item` 可读取受限的源数据行。打开 Outputs 页直接展示已有结果，不重复推导。首次推导可能启动 worker。参数不完整或推导失败时明确提示，不保留过期字段，也不猜测未知结构。
8. 点击 **Export job YAML** 保存可供 Shell / SDK 提交的标准 Job，导出会打开 YAML 预览窗口，默认勾选 **Include designer metadata**，可取消以只导出执行定义；提供 **Copy**、**Print** 和 **Download**。后续可以通过 **Load YAML** 加载以前导出的 Job，或者在 Job YAML 面板编辑后点击 **Apply YAML**。导入接受公共 Job 格式；内部子作业路由供服务执行使用。

浏览器使用本机 localStorage 自动保存草案；不同浏览器不共享草案，没有服务器端历史作业存储。加载“历史文件”指上传已有 YAML 文件，不是从运行看板拉取执行历史。设计器不在导出时执行推导；导出检查图结构，实际输入端口及 schema 校验由设计时推导提供。布局支持平移滚动、缩放和适应视图。

提交示例：

```text
submit workflow.yaml --vars variables.yaml
```

下侧 **Logs** 面板记录当前浏览器会话的操作提示、API 请求起止与耗时、请求错误、结构推导诊断以及浏览器异常，按 Info / Warning / Error 分类，可过滤、清空。日志按时间从新到旧排列，新消息显示在顶部；开启自动跟随后保持在顶部，关闭后新增消息尽量保持原阅读位置。上方状态栏仅显示最新一条消息，完整消息保留在日志面板。日志只在浏览器内存中保留最近 1,000 条，不写入磁盘；页面刷新后清空。节点和服务进程的完整后台日志仍保存在各自日志文件中。

## 对话编排

Chat 连接兼容 Chat Completions 的大模型 API。用户描述一段完整的数据处理或分析需求，再通过问答补充信息。信息充分后，助手提出完整 Job YAML 和保留类型的变量。它不执行 Job。检查提议后点击 **Apply proposed changes** 才更新画布，并且可以 Undo。

在 Designer 服务端配置完整 API 地址和模型：

```yaml
chat:
  url: https://model.example/v1/chat/completions
  model: your-model
  token_env: XSTREAM_MODEL_API_KEY
```

启动服务前设置指定的环境变量，用作 Bearer 凭据，不会发送到浏览器。无需凭据的模型服务可省略 token_env。未配置 chat 时编辑器正常工作，Chat 不可用。Chat 通过配置的大模型 API 生成 Job 提议。

需求示例：“读取气象观测，保留有效且超过温度阈值的记录，阈值使用变量；合并各区域数据流并输出 CSV。”请提供实际 Item 路径，或先展开相关 Catalog 文件夹。模型收到当前草案、变量、可用算子声明、最多 100 个已浏览 Item 的描述和最近 12 条对话。目录按需加载，因此这不是完整清单。不自动发送数据内容和输出样例。路径、语义或所需算子不明确时，助手应继续提问而不是编造。元信息和对话会发送至配置的模型服务。

模型返回包含 reply、job_yaml 和 variables_yaml 的 JSON 对象。澄清问题时两个 YAML 字段均为 null，画布保持原样。提出方案时提供完整 YAML 字符串，使用逻辑流连接和可用算子。不合法、被截断或使用不支持算子的提议不能应用。模型请求超时为 90 秒，响应上限 1 MiB。

第三方模型提供者仍可实现 xstream.designer.ChatPlanner，通过 Java ServiceLoader 注册到 META-INF/services/xstream.designer.ChatPlanner。将提供者 JAR 放入 plugins/，使用 `java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*:plugins/*" xstream.designer.Main CONFIG.yaml` 启动，并通过 chat.provider 选择名称。上面的常规配置直接调用模型 API，不需要另建适配服务。

## 接口与开发

HTTP API 不要求网页用户认证，仅供本设计器使用。除配置与静态资源外，使用 JSON POST：

| 路径 | 功能 |
|---|---|
| `GET /api/config` | 公开服务、仓库名称与聊天能力，不返回凭据文件 |
| `/api/operators` | 使用 `repository` 查询仓库实时算子列表 |
| `/api/datasets`、`/api/items` | 读取所连接服务的目录与数据项元数据 |
| `/api/document`、`/api/mapping` | 解析／规范化 Job、参数或变量 YAML |
| `/api/export` | 检查图结构，返回 Job 与变量 YAML |
| `/api/infer` | 使用 `job_yaml`、`variables_yaml` 请求真实结构推导 |
| `/api/chat` | 基于当前草案、消息和实时目录生成候选草案 |

POST 请求上限 1 MiB，仓库列表上限 4 MiB；不接受浏览器传入任意代理地址。CA 与 token_file 是服务端文件。页面只读取目录和结构，不提供作业执行、仓库发布或用户管理 API。

后端用 Scala 实现，浏览器交互使用随包发布的原生 JavaScript / SVG / CSS，不依赖外部 CDN。测试覆盖 YAML 往返、变量类型、循环与重复消费、空输出、真实 Arrow schema 展示、仓库查询、聊天草案及凭据不外泄；需配合浏览器测试验证拖放与连接、导入导出和实际 Union 推导。

浏览器回归测试（需要 Playwright 和 Chromium）：

```bash
python3 scripts/test-job-designer.py --url http://localhost:8840 \
  --item-uri dacp://localhost:19860/DATASET/observations
```

从仓库根目录执行，将 URI 换成实际 records Item，要求包含 `quality` 列。测试只推导结构，不执行作业。

属性面板采用固定右侧栏，默认隐藏，双击步骤或选择 Outlet 时展开，占用独立列而不遮挡画布；关闭后空间还给画布。左右侧栏均通过边缘窄条箭头折叠与展开。右侧展开时显示 `>`，折叠后显示 `<`；左侧方向相反。点击画布空白处、连线或按 Esc 不会自动隐藏属性侧栏。顶部直接展示具有清晰边框和立体层次的 **Step** 与 **Parameters** 标签页。底部编辑区默认收起，点击标签展开，使用箭头按钮收起；画布优先占用窗口空间，页面本身不滚动。

属性面板分为 **Step**、**Parameters** 和 **Outputs** 三个标签页。Parameters 根据算子必需的静态 `parameters` 契约生成：名称和类型来自声明，同时显示说明、默认值、枚举及范围。可选参数需显式启用，未启用的参数不写入 Job。对象、数组及完整值变量（`$VAR_NAME`）通过表单配置。属性和参数的合法修改自动保存并同步 YAML，保留类型；连续输入合并为可撤销的编辑操作。详见[参数契约](https://github.com/rdcn-link/xstream-codex/blob/main/docs/operator-parameters_zh.md)。

启动配置提供 `local_catalog`、`nodes` 和 `repository`。浏览器 Target 默认为 Local，可切换到配置节点；不能输入任意服务地址或凭据。打开页面时独立加载两个服务，精简页头显示状态及故障信息；上游恢复后刷新页面即可重试。

点击流连线后高亮选中，可在画布获得焦点时按 Delete / Backspace 删除；选中时仅高亮连线，不出现矩形框或额外的工具栏按钮，支持撤销和重做。在表单输入框中编辑文字不会触发连线删除。一个已连接到下游步骤的端口不能同时作为 Job 输出，需要先删除该连线。导入文件中的输出绑定也会显示为连到 Outlet 的线。

选中步骤后按 Delete，会删除该步骤及其相关连线和 Outlet 连线；选中连线后按 Delete，只删除该连线。撤销可恢复完整删除前状态，重做可再次删除。编辑参数或名称等表单字段时不会触发图形删除。

左侧算子库采用可折叠分类与图标网格，直接读取管理员审批指定的多个 `categories` 及开发者提供的 `icon`，用于分类展示和图标渲染。搜索会自动展开匹配分类，清空搜索后恢复原展开状态。悬停或键盘聚焦图标可查看版本、说明、输入端口、参数类型和执行器；拖入画布、双击或按 Enter 添加步骤。

拖动已有步骤仅调整位置，不会展开已折叠的属性侧栏；单击步骤只选中，双击才展开其属性。

在画布空白处按住左键拖动可框选完全位于选框内的步骤和 Outlet；Shift 框选或 Shift 单击步骤追加或切换选择。多选后拖动任一所选元素整体移动，Delete 一次删除所选步骤及关联连线，可撤销恢复；Outlet 不会被删除。框选与拖动均不会展开属性侧栏，缩放和滚动后同样有效。



选中步骤或连线后，Delete 和 Backspace 均可删除；选中多个步骤时批量删除。编辑输入框、文本区或参数值时不会触发画布删除。画布获得键盘焦点不显示整块蓝色外框。

删除多个步骤前显示确认框；取消时不改变选择、作业和撤销历史，删除单个步骤或连线无需确认。画布上方工具栏中的 Undo / Redo 按钮及快捷键支持撤销和重做：Ctrl/⌘+Z、Ctrl/⌘+Shift+Z，也支持 Ctrl+Y 重做。批量删除和有效移动分别作为一次操作整体恢复。文本输入框内保留原生文本撤销行为；拖动过程中不切换历史。

拖动步骤或 Outlet、以及从算子库新增步骤时，与其重叠的其他步骤及 Outlet 会自动向下避让，并在需要时连锁调整，保留间距。多选步骤保持相对位置，停留在拖动目标位置。每次预览均基于拖动开始前的布局重新计算，移开鼠标后邻近元素恢复；取消拖动会恢复全部位置且不修改历史。放下后整次移动及避让作为一次操作支持撤销和重做。连线不计入放置障碍。

步骤框的右上角显示算子声明的图标及颜色，与左侧算子库一致；内置 `read_item` 使用相同的数据源图标。尚未加载到算子元信息时显示灰色占位图标。

Parameters 使用紧凑表单，参数名为固定标签。已声明单一类型的参数显示类型标注和小型 `$` 变量切换按钮。说明、默认值与约束通过信息提示查看；可选参数用复选框启用。仍支持多行字符串、嵌套对象、数组、枚举及联合类型。自定义参数仅在新增时输入名称，已有参数名均显示为标签。

左侧栏将 **Operators**、**Catalogs** 分成两个标签页，标签以带图标的圆角按钮沿侧栏左缘从上到下排列，内容显示在其右侧，与右侧横向属性标签区分。默认打开算子页；切换时保留搜索、分类展开、数据集展开及各页滚动位置。支持上下方向键、Home 和 End 切换标签。

打开页面时先加载目录根；展开时才加载数据集和文件夹。切换标签复用已加载的列表及展开项目；刷新页面可重新加载目录根。加载期间合并重复请求，空目录或服务不可用时显示明确状态。

### 导出与恢复布局

`designer.version: 1` 是布局格式版本；`generator.name` 和 `generator.version` 标记设计器名称及构建版本，`exported_at` 是本次导出时的 UTC 时间（ISO 8601）。`positions` 保存步骤坐标，`outlet` 保存结果出口位置，`viewport` 保存缩放和滚动位置。重新加载会恢复这些布局信息；没有辅助区的文件自动排列。执行端接受并忽略整个 `designer` 区，不影响执行语义。预览窗口切换复选框后，复制、打印、下载均使用当前预览内容，变量仍单独导出。

设计器本地推导需要 Docker 访问权限；`operator_cache`、`operator_max_bytes`、可选的 `plugin_directory` 和 `inference_workers` 配置本地运行环境。worker 空闲后自动回收。仅重新推导变化的步骤及其下游；当前浏览器编辑会话内复用未变化的结果，刷新页面会开始新的会话；失败时显示诊断信息，不转发到执行节点兜底。

### YAML 编辑器

Job YAML、Variables 和只读导出预览统一使用 CodeMirror 6。支持 YAML 语法着色、变量着色、行号、代码折叠和搜索；可编辑区域还支持搜索替换、两空格缩进、Tab 缩进、编辑器内撤销/重做和语法错误标记。编辑器内快捷键不会操作画布历史。语法提示仅检查 YAML 语法，作业结构与参数规则仍由 Apply YAML 和服务端检查；不会因此自动执行任务或推导。

画布变更会同步到 Job YAML；手动修改 YAML 后，在 Apply YAML 之前不会被画布同步覆盖。导出预览只读，辅助信息开关、复制、打印和下载使用同一份原始 YAML 内容，不包含行号或着色标记。

参数名称后的问号用于查看参数说明。鼠标移入、键盘聚焦或点击问号时，参数面板下方的说明区显示该项的描述、类型、是否必需、默认值、枚举与约束；内容保留到查看下一项或切换步骤。嵌套参数共用这个说明区。

前端源码在 `frontend/`，依赖版本由 `package-lock.json` 锁定。修改编辑器源码或依赖时，使用 Node.js 22 或以上版本运行：

```bash
cd xstream-designer/frontend
npm ci --ignore-scripts
npm run build
```

将生成的 `src/main/resources/web/yaml-editor.js` 和 `editor-licenses.txt` 与源码一起提交。之后在模块目录执行 `sbt test stage`；常规 Scala 构建和部署无需 Node.js。资源由 Scala HTTP 服务本地提供，浏览器不访问 CDN。第三方许可保留在 `/editor-licenses.txt`。

### 输出结构与样例

输出预览位于右侧 **Outputs** 标签页。参数和连线变化自动更新各端口的结构。未接齐输入的步骤单独显示缺失端口，上游及无关分支的成功结构不受影响。

样例由算子的 `previewOutputs`（JVM）或 `preview_outputs`（Python）接口提供，不由界面依据 Schema 合成。所有算子必须实现样例接口；当前输入无法生成样例时明确报告原因。每个端口默认最多 10 行、16 KiB（按序列化后的 UTF-8 JSON 计），单次图预览的样例总量最多 256 KiB；截断会明确标记。结构推导与样例请求分离：结构逐步骤返回后，后续样例请求附带 `preview: {max_rows: 10, max_bytes: 16384}`。样例方法不调用完整作业的处理入口。`read_item` 的样例来自受限源读取，records 保留字段结构，bytes 用 Base64 展示。详见[结构推导与样例接口](https://github.com/rdcn-link/xstream-codex/blob/main/docs/structure-inference_zh.md)。

`read_item` 未显式选择 `view` 时，根据 Item 元信息优先选择 records，不支持时使用 bytes；显式选择始终保留。设计器展示自动选择的实际视图，YAML 中省略自动选择的 `view`，执行端也采用相同规则。例如 `dacp://localhost:19860/ds-6zmr-crvh/benchmark` 的 records 视图可返回站点、时间、温度、湿度及质量字段的 Schema。

步骤框内仅以紧凑的字段名和类型展示输出结构，不展示数据样例；完整结构及算子提供的样例统一在右侧 **Outputs** 标签页查看。

结构推导按步骤跟踪。算子、参数、输入绑定和被引用的变量发生变化时，使该步骤及其下游结果失效；独立分支和上游结果继续展示并复用。删除下游步骤不会重新推导保留的步骤。移动、选中、重命名、调整步骤顺序、切换侧栏或修改 Job 输出绑定不会触发推导。结构与样例结果分别跟踪。服务端比较变量解析后的参数和依赖指纹，因此修改无关变量不会调用未变化的算子。

Schema 的界面类型名称参见[结构推导文档](https://github.com/rdcn-link/xstream-codex/blob/main/docs/structure-inference_zh.md#界面中的-schema-类型名称)，步骤框与 Outputs 统一展示，保留嵌套层级、时间单位与可空性。

步骤框的结构采用紧凑的两列表格，分别展示字段名和统一类型名，无额外表头。字段较多时在表格区域滚动，长字段名和类型省略显示，悬停查看完整内容；右侧 Outputs 提供完整结构。

推导错误在步骤框中仅显示错误图标和 `Inference error`，未知结构显示问号图标和 `Schema unknown`；推导中显示 `Inferring…`。详细错误在右侧 Outputs 和下侧 Logs 中查看。

Outlet 最多连接一个步骤的一个输出端口；连接新端口会替换原绑定，允许暂时不连接。导入和导出均校验此限制。结构推导先返回 Schema，再单独请求有界数据样例；样例读取较慢或失败不影响已展示的结构。

## 校验、逐步骤推导和编辑操作

点击 **Validate**，下侧 **Validation** 页会列出错误和警告，点击问题中的步骤名称可定位并打开该步骤。检查包括：未连接 Outlet、没有流向 Outlet 的游离步骤、缺失或多余输入端口、不存在的步骤引用、重复消费、环路、参数及变量。已有推导结果还用于检查输出端口和输入表示／格式是否匹配；没有推导结果时显示未验证警告，不表示验证通过。校验不执行作业、不启动推导 worker、不读取数据，不能保证实际执行成功；当前未应用的 Job YAML 不参与画布校验。

设计器通过 `/api/infer_stream` 逐步骤接收 NDJSON 事件：`step` 携带步骤名与该步骤推导结果，`complete` 携带完整报告，`error` 表示请求未正常完成。推导依赖从上游到下游，完成一个步骤立即更新画布；当前仍串行执行独立分支。发生请求错误时保留本次已返回步骤的结构，过期草案结果被丢弃。有界样例随后单独获取，records 样例在 Outputs 中按字段列和记录行展示为表格；嵌套值用紧凑 JSON 展示，长值悬停查看，bytes 样例显示 Base64。

选中单个步骤后，点击 **Duplicate step** 或按 **Ctrl/⌘+D** 创建副本。副本使用新 ID，保留算子、参数，不复制输入连线和 Outlet 绑定，避免重复消费。可撤销复制。框选或 Shift 单击多个步骤后，拖动其中任一步骤的标题栏可整体移动，保持相对位置，连线跟随，支持整组撤销；选中的 Outlet 也可一起移动。

编辑会话的结果复用仅保存在内存中，不同浏览器会话相互隔离，30 分钟没有推导请求后过期。最多保留 32 个会话，每个会话保存最新步骤结果，合计不超过 2 MiB。这不会为节点或 Union 的推导接口增加持久化结果缓存。如需在不修改参数的情况下刷新源数据元信息，可重新加载页面。

左侧提供 **Operators**、**Catalogs** 和 **Examples** 三个标签页。画布展示处理步骤、流连接和 Outlet。本地读取使用 context URI；远程读取使用服务公开 URI，目标为 Union 时使用其公开名称。

数据浏览器以树形展示 Target catalog 与配置的 DACP 服务。初始只列出目录入口，展开时才读取 Dataset 和 Folder Item 的直接子项。选择 Target catalog 中的 Item 时，设置 `read_item.params.uri: context:/dataset/item`；选择其他已配置目录中的 Item 时，保留完整的 `dacp://host:port/dataset/item` URI。context 引用属于最终执行的 JVM，因此其目录需要与本地预览目录匹配。节点凭据配置在 `nodes` 中，仅保留在设计器服务器。可选 SQL 使用 `FROM source`，结构和样例预览反映查询结果。

```yaml
local_catalog:
  datasets:
    weather:
      provider: directory
      source:
        root: /srv/weather
nodes:
  - url: dacp://node-a.example.org:18861
    ca: /etc/dacp/ca.crt
    token_file: /etc/dacp/node-a.token
```

设计器通过 XStream 插件契约绑定 JVM 级 DataCatalog。可用 `local_catalog.data_catalog` 配置自定义 DataCatalogProvider，替代静态数据集。详见 [XStream 引擎](../docs/xstream_zh.md)和 [Job 读取参数](../docs/jobs_zh.md)。

目录浏览以当前实现为准：左侧 **Catalogs** 初始只加载本地目录及配置的服务根；展开后才加载数据集和文件夹。Target 默认为 Local；上下文目录随 Target 切换，该目录的 Item 使用 `context:`，其他目录使用完整 DACP URI。`read_item` 的结构及样例读取使用对应目录。

Catalogs 目录树分别用主机、数据集、文件夹和数据文件图标区分层级。启动时加载目录根，展开时加载子项。

Item 行仅显示末级名称与文件/文件夹图标。悬停或键盘聚焦时可查看完整路径和支持的视图。点击 Item 后，在独立悬浮层中显示紧凑的视图和复制图标，默认位于名称末尾右下方，空间不足时位于上方；不扩大 Item 行的高度或高亮范围。选择视图会设置 `read_item` 参数，复制路径不修改 Job。按 Esc 或点击外部隐藏图标。

[科学视图选择、结构预览及样例](../docs/designer-scientific_zh.md)：支持数组、序列的插件式表单及紧凑展示。

Item 操作浮动框显示在名称末尾的右下方，独立于列表行。文件夹仅点击左侧箭头展开或收起；点击名称只显示操作。Item 行采用紧凑间距。

## 官网内置演示

可选 `showcase` 配置读取 `job_file` 和可选 `variables_file`，作为首次访客的默认草稿。两者是服务器本地 YAML 文件，启动时校验，每个文件最多 1 MiB。浏览器仅获得公开内容及 `label`，不包含配置文件路径。启用后草稿使用独立存储键，刷新后保留，且仅属于各自访客。Load demo 通过普通可撤销导入恢复默认草稿和变量。已有草稿优先于默认示例。文件中只能放公开示例数据。

```yaml
showcase:
  job_file: /srv/demo/showcase.yaml
  variables_file: /srv/demo/variables.yaml
  label: Load demo
```

前端资源及 API 使用相对 URL，允许在同源反向代理的 `/demo/` 等前缀下运行。代理移除前缀、保留公开 Host/Origin 组合，并流式转发增量响应，不等待完整推导报告。独立 XStream 官网提供自己的 Designer 发行目录、示例目录和部署监督程序，本服务不需要连接生产节点。

## 步骤描述与算子可信度

Step 属性页提供多行 Description，最多 8192 个字符，自动保存为步骤的 `description` 字段。描述支持复制步骤、撤销/重做、导出和重新加载；不影响结构推导或执行行为，关闭设计器辅助信息后导出仍保留。

算子列表的悬浮详情显示仓库公开的可信度及下载次数。可信度采用五个盾牌勾选图标，按分数着色；画布步骤不显示可信度。可信度不写入 Job，也不代表安全保证。

左侧算子列表按名称合并版本，仅展示最新公开版本；数字段按数值排序（`1.10` 高于 `1.9`），正式版高于其连字符预发布版本。已有 Job 使用的旧版本继续按原版本解析，不会自动升级。

鼠标悬停在数据集上时，显示其描述 JSON 中的 `title` 和 `description`；目录树仍显示用于访问的数据集名称。

Designer 用于编制逻辑 Job：选择 Item、连接算子、配置参数、查看结构并导出 YAML。

算子列表显示图标、名称和版本。悬停或聚焦算子时，浮动详情显示可信度盾牌、分数及当前版本程序包的下载次数。缺少计数时显示 Unavailable；内置算子显示 Not applicable。

Target 属于浏览器会话，默认为 Local。目录、结构与样例请求携带 `target`（`local` 或配置节点 URL）。服务端根据 `nodes` 校验选择，并保密凭据。切换 Target 会更新所选设计上下文的目录、结构及输出样例。

## 确认及记住 Target

点击 **Target: … Change…**，选择已配置服务，再点击 **Use selected target**。仅修改窗口中的选择不会切换上下文；点击 **Keep current target** 或按 Escape 保持当前目标。切换 Target 会刷新依赖上下文的结构和样例。推导使用有效的浏览器会话标识，并根据确认的 Target 解析 `context:`。

Job YAML 可保存设计目标：

```yaml
designer:
  version: 1
  target: dacp://node-a.example.org:18861
```

`local` 表示 Designer 服务器的本地目录。主动加载 Job 时提示确认设计目标。刷新页面时直接恢复草稿中已确认且仍在配置中的目标，不再弹窗；若该目标已从配置移除，则使用 Local 恢复草稿，并提示检查 context Item 路径。不会连接未配置的地址。设计目标不可用时，提示选择已配置目标或保留当前目标。导出保存已确认的 Target；取消包含设计器元信息时，也会去掉该设计目标。执行引擎忽略整个 `designer` 区域：此字段不路由、不提交 Job，也不授予权限。

## 示例 Job

左侧 **Examples** 标签页列出十个随包提供的 Job：记录流读取、筛选并导出 CSV、变量化流程、双流合并、NetCDF 数组视图。每张卡片说明用途和步骤数。**Load example** 将图和变量值加载到当前 Target，不执行或提交 Job。当前画布有内容、存在未应用的 YAML 或变量值时，需要确认替换；Undo 可恢复原来的图和变量。

示例通过 `context:` 引用样例 `weather` 数据集。请根据所选 Target 修改 Item 路径或变量。合并示例将同一个样例 Item 读取两次，因此仅有一个样例目录也能使用；修改任一 URI 即可合并不同来源。卡片会提示不可用的算子版本，并在配置仓库提供对应版本前禁用加载。仅使用内置 `read_item` 的示例不依赖仓库算子。

`/api/examples` 返回随包提供的 YAML、变量、描述及依赖算子引用。示例文件和索引位于 `xstream-designer/src/main/resources/examples/`；所有示例均校验 Job 定义及变量解析结果。资源中不包含服务凭据或执行节点分配。

示例还包含本地与远程数据流合并，以及保存远程设计目标的 Job。请将 example.org 示例地址替换为配置的服务地址。远程读取不代表计算下推；分布式执行仅由 Union 规划。`designer.target` 是仅供设计器使用的元信息，执行时忽略。

结构推导在调用算子之前，使用 Variables YAML 替换完整的 `$VAR_NAME` 参数值，并保留数值、布尔值及结构化值的类型。修改变量值会更新受影响步骤及其下游的结构。缺失变量会按名称报错；在 Variables 中补齐后可恢复推导。导出的 Job 保留变量引用。

跨节点模板包括两个远程节点、三个远程节点和 Union 协作。前两者保存 `designer.target: local`，Union 模板保存远程 Union 目标。使用前替换示例地址和公开数据集路径；加载时需确认目标切换。同一个 Job 可提交给 Union 自动分配执行位置，设计器不指定步骤所在节点。

### 部署专用示例

在启动配置中设置 `examples_directory: /srv/xstream/designer-examples`，提供 `index.yaml` 及其引用的 Job 和变量文件。索引采用 `id`、`title`、`description`、`job` 和 `variables` 字段，与随包索引一致。启动时读取并校验所有文件，每个文件限制为 256 KiB，只接受目录内的简单 YAML 文件名。不配置时使用随包的样例目录示例。

部署时必须填写实际的 Item 路径、已配置的服务地址和 Union 公开数据集名称。本地示例应保存 `designer.target: local`，确保在远程设计上下文加载时提示切回本地。远程示例保存对应的已配置服务目标。应将每个示例的变量和目标一起传给 `/api/infer` 验证，包括源结构和下游诊断。示例中不得发布凭据。

示例卡片展示紧凑的特征标签，包括 **Cross-node**（跨节点）、**Variables**（含变量）、**Local target**（本地目标）、**Remote target**（远程目标）、**Union**、**Multi-input**（多输入）和 **Scientific array**（科学数组）。在索引条目中配置 `tags: [Cross-node, Variables, Remote target]`；标签仅用于展示，不影响执行设置。每个示例最多八个标签，每个标签最多 40 个字符。

示例按索引顺序显示 `01`、`02`……编号，便于引用。编号表示展示位置，示例的稳定标识仍为 `id`。
