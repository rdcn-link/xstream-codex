# Plugins and module boundaries

[中文](plugins_zh.md)

Built-in implementations and contributed plugins use the same extension contracts. Core owns discovery, lifecycle and resource management; plugins own domain behavior and parameter validation.

| Extension | Implementation and guide |
|---|---|
| DataCatalog / Dataset / Item / DataView | [xstream-data](../xstream-data/README.md) |
| OperatorExecutor | [xstream-plugin-operator-executors](../plugins/xstream-plugin-operator-executors/README.md) |
| SourceAnalyzer / SourceArchive / SourceBuildRecipe | [xstream-plugin-source-analysis](../plugins/xstream-plugin-source-analysis/README.md) |
| ChatPlanner | [xstream-designer](../xstream-designer/README.md) |

Implement public no-argument classes and list them in `META-INF/services/<fully-qualified-interface>`. Add the plugin JAR and dependencies to the host classpath and restart; do not edit a central registry. Executor contracts currently come from the DACP integration API as `dacp.OperatorExecutor`; bundled implementations use `xstream.operator.executors`. Source-review contracts use `xstream.operator`. Registration filenames must match the actual interface names.

Scientific views preserve arrays and sequences rather than silently flattening them into records. Keep format dependencies in their data plugins. Operator code runs in isolated environments; host-installed plugins themselves are trusted code. Include configuration examples, registration instructions and contract tests for contributions; module guides list current validation commands.

## Local development

The standalone engine supports source-directory references, reusable builds and direct source submission; see [local operator libraries](local-operators.md). `LocalOperatorBuilder` is an implemented build extension point with built-in `python` and `command` providers registered through ServiceLoader.
