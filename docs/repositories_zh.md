# 构建 XStream

[English](repositories.md)

使用以下命令构建 Runner、Designer 和相关库。

| 模块 | 职责 |
|---|---|
| `xstream-data` | 数据目录、数据访问、Arrow 流及格式插件 |
| `xstream-execution-api` | 流、推导、执行器、历史和物化契约；JSON/YAML 工具 |
| `xstream-operator-sdk` | JVM/Python 开发 SDK 和元数据验证 |
| `xstream-operator-inference` | 按需启动、可复用的推导 worker |
| `plugins/xstream-plugin-operator-executors` | 容器、JVM、Python 和原生命令启动实现 |
| `plugins/xstream-plugin-execution-file` | 文件执行历史和 Arrow 流物化存储 |
| `connectors/xstream-dacp-protocol` | 远程数据编码及 TLS 客户端传输 |
| `connectors/xstream-dacp-client` | Runner 与设计器使用的远程目录、Item 和 Job 客户端 |
| `xstream-engine` | 嵌入式执行及 `xstream-runner` |
| `xstream-designer` | 可视化编排及预览 |

使用 JDK 17 和 sbt 1.10.x。运行 `scripts/bootstrap-local.sh` 发布开发依赖，再运行 `sbt test`。通过 `(cd xstream-engine && sbt stage)` 和 `(cd xstream-designer && sbt stage)` 生成应用发行目录。制品组织名为 `io.github.rdcn-link`，版本为 `0.1.0-SNAPSHOT`。


数据契约测试位于 `xstream-data/xstream-data-tests`。`scripts/test-designer-*.py` 浏览器测试需要 Playwright 和 Chromium。执行 `python3 scripts/check-doc-translations.py` 检查翻译配对。第三方格式依赖属于插件，详见[插件开发](plugins_zh.md)。
