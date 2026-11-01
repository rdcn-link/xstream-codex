# 本地算子库

[English](local-operators.md)

独立 XStream 除了仓库已发布算子，也支持本地算子源码目录。本地开发不需要算子仓库、提交令牌、审核或可信度评分。处理逻辑、输入输出端口、参数、结构推导和样例使用与发布算子相同的 SDK。运行阶段仍使用已有的隔离共享 Docker 镜像，不会将算子代码加载到引擎 JVM 执行。

## 目录与 Job 引用

```text
temperature-filter/
├── operator.yaml        # 可提交到仓库的源码声明
├── local.yaml           # 本机运行环境与构建配置
├── src/                 # Python、Java 或 Scala 源码
└── build.sbt            # 可选构建工程，也可使用 pom.xml
```

步骤显式引用源码目录：

```yaml
name: Local analysis
tasks:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params: {uri: 'context:/weather/observations.csv', view: bytes}
  - id: filter
    operator: {local: ./temperature-filter}
    inputs: {source: read}
    params: {min_temperature: 20}
outputs: {result: filter}
```

`operator.local` 不能与内联源码定义混用。普通 `operator: name@version` 使用仓库和包查找。采用 `operator: {local: name@version}` 时，从 `local_operator_library/name/version/` 读取源码；目录中的名称和版本必须与 `operator.yaml` 一致。

Runner 的相对目录引用以 Job 文件所在目录为基准，而非启动进程工作目录。嵌入的 `LocalEngine` 使用 `job_working_directory`，默认是进程工作目录；Runner 配置显式指定该项时覆盖 Job 目录。`local_operator_library`、`operator_cache`、数据根目录等配置路径仍相对于进程工作目录。

## 可发布声明

`operator.yaml` 与仓库源码提交使用同一套字段：

```yaml
name: temperature_filter
version: '1.0'
description: Filter observation bytes.
language: python
runtime: python-arrow@1.0
entrypoint: temperature_filter
source_public: true
inputs:
  source: {representations: [bytes], formats: []}
parameters:
  type: object
  properties:
    min_temperature: {type: number, default: 20, description: Minimum temperature.}
  required: []
  additionalProperties: false
icon:
  path: M4 4H20L14 12V20L10 18V12Z
  color: '#14866b'
```

在 `src/` 下使用 `BytesOperator`、`ByteStreamOperator`、`RecordsOperator` 或底层 SDK 编写代码。支持无输入的数据源和无输出的终止步骤。输出端口由执行及结构推导方法给出，不在清单中重复声明。模板提供结构推导和有界样例实现，见[算子编写指南](operator-authoring_zh.md)。

`runtime` 表示计划发布时使用的仓库运行环境；`local.yaml` 指定本地对应镜像，开发者负责保证依赖一致。本地执行不会连接仓库验证这种对应关系。运行镜像必须已经安装。本机构建以调用者权限执行，应只运行可信的源码项目。

## Python

```yaml
# local.yaml
image: xstream/operator-python:1.0
executor: python
build:
  provider: python
```

Python 构建器将 `src/` 打包成可导入 ZIP，包含辅助模块及包内数据。它不安装 requirements 或解析依赖；依赖和 Python SDK 必须由运行镜像提供。源码及入口与仓库构建时使用的模块一致。

## Java 与 Scala

```yaml
# local.yaml
image: xstream/operator-jvm:1.1
executor: jvm
build:
  provider: command
  command: [sbt, package]
  artifact: target/scala-2.13/local-scala-identity_2.13-1.0.jar
```

采用普通 SDK 工程构建薄 JAR。Maven 工程可使用 `command: [mvn, package]`，并将 `artifact` 改为实际 JAR 路径。命令是参数数组，不进行 Shell 展开。构建在算子目录内运行，产物必须位于该目录内，然后复制到不可变缓存。本机需要安装构建工具、构建依赖和匹配的 SDK，运行镜像负责执行依赖。构建工具配置仅用于本地编译；仓库采用自己的语言构建插件和注册构建环境，不执行提交者的构建命令。

## 缓存、取消与调度

成功构建依据源码树、声明及本地配置缓存到 `operator_cache/local-builds/`。源码变更会生成新产物，失败或取消的构建不生成有效缓存项。源码指纹排除标准生成目录 `target`、`build`、`dist`、`.git`、`.venv`、`__pycache__`、`.xstream-operators`、`.xstream-executions` 以及指定构建产物。缓存应放在源码树之外。源码最多 16,384 个文件、64 MiB，不允许符号链接。外部工具链变化后需删除对应构建缓存。

取消与 Job 超时覆盖构建及执行。构建诊断写入 stderr，stdout 仅保留结果流。本地算子固定在当前引擎，并形成同进程合并边界；源码路径和构建命令不会提交到远端。符合条件的纯仓库算子上游区域仍可下推。DACP 和 Union 远程提交拒绝本地目录步骤，包括 DEBUG 模式。这是可信本地开发能力，不是远程任意命令执行接口。

可信嵌入程序可通过 `LocalEngine.inferLocal(reference, request, cancel)` 请求结构及样例，复用相同构建产物和推导 worker。执行不会自动调用推导。目前设计器尚未提供本地算子面板。

## 运行完整示例

从项目根目录按[初始化说明](../README_zh.md)构建 Runner，发布或安装匹配的 SDK。确保 Python/JVM 镜像已安装，PATH 中存在 sbt：

```bash
cd xstream-engine
sbt stage
cd ..
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/local/engine.yaml examples/operators/local/job.yaml
```

示例读取本地 Item，用 Python 将字节转为大写，然后由 Scala 传递流，无需算子仓库。仅源码构建、算子产物、运行资源和执行历史使用磁盘，中间数据保持流式传递。

## 发布同一份源码

```bash
xstream-operator submit ./temperature-filter \
  --url https://operators.example.org --token-file /protected/submitter.token
```

工具读取 `operator.yaml`，仅打包 `src/` 及可选的 `resources/`，排除 `local.yaml`、构建产物和其他本地文件。仍支持显式的 `submission.yaml --source archive.zip` 提交形式。声明引用的许可证、SBOM 文件单独上传。仓库验证运行环境、扫描和构建源码，最后由管理员审核。本地构建成功不会豁免发布要求，也不保证兼容所选仓库运行环境。

审核通过后，将 `operator: {local: ./temperature-filter}` 换为 `operator: temperature_filter@1.0`；输入、参数和源码保持一致。

## 扩展构建器

实现 `xstream.operator.LocalOperatorBuilder` 的 `id` 与 `build(directory, settings, destination, check)`，并在 `META-INF/services/xstream.operator.LocalOperatorBuilder` 注册。将实现打包为插件 JAR，配置 `plugin_directory`。构建器验证自己的参数，在长任务中调用 `check()`，并返回 `destination` 内的普通产物文件。内置 `python`、`command` 使用相同契约，新增构建器无需修改引擎注册表。构建器属于可信本机代码，见[插件指南](plugins_zh.md)。
