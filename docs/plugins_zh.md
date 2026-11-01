# 插件与模块边界

[English](plugins.md)

XStream 的内置实现和外部插件使用同一套扩展契约。核心负责发现、生命周期和资源管理，插件负责领域实现及自身参数校验。

| 扩展 | 实现与说明 |
|---|---|
| DataCatalog / Dataset / Item / DataView | [xstream-data](../xstream-data/README_zh.md) |
| OperatorExecutor | [xstream-plugin-operator-executors](../plugins/xstream-plugin-operator-executors/README_zh.md) |
| SourceAnalyzer / SourceArchive / SourceBuildRecipe | [xstream-plugin-source-analysis](../plugins/xstream-plugin-source-analysis/README_zh.md) |
| ChatPlanner | [xstream-designer](../xstream-designer/README_zh.md) |

实现公开的无参类，并在 `META-INF/services/<接口全名>` 中列出实现类。将插件 JAR 及依赖加入宿主类路径后重启，不修改中央注册表。执行器契约由 `xstream-execution-api` 的 `xstream.execution.OperatorExecutor` 提供，内置实现位于 `xstream.operator.executors`；源码审核契约位于 `xstream.operator`。注册文件名称必须与实际接口全名一致。

科学视图保持数组和序列结构，不能静默展平成 records。第三方格式依赖留在各自数据插件中。算子代码在隔离运行环境中执行；宿主安装的插件本身属于受信任代码。新增实现应包含配置示例、注册说明和契约测试；各模块手册提供现有测试命令。

## 本地开发

独立引擎支持源码目录引用、可复用构建和直接提交源码，详见[本地算子库](local-operators_zh.md)。其中 `LocalOperatorBuilder` 是已实现的构建扩展点，内置 `python` 与 `command` 提供者通过 ServiceLoader 注册。

## 依赖环境插件

`DependencyEnvironmentProvider` 通过 ServiceLoader 发现。内置 `python`、`jvm`、`native` 实现位于 `plugins/xstream-plugin-dependencies`。注册、配置和契约测试见[依赖环境指南](dependency-environments_zh.md)。本地构建器另提供 `jvm` 实现。

## 远程数据与执行目标

`RemoteItemProvider` 与 `ExecutionTargetProvider` 是独立的 ServiceLoader 扩展点，定义于 `xstream-execution-api`。注册、配置、资源契约与测试见[执行目标](execution-targets_zh.md)。
