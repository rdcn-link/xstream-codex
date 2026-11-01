# Building XStream

[中文](repositories_zh.md)

Build the runner, Designer and libraries with the commands below.

| Module | Responsibility |
|---|---|
| `xstream-data` | Catalogs, data access, Arrow streams and format plugins |
| `xstream-execution-api` | Stream, inference, executor, history and materialization contracts; JSON/YAML utilities |
| `xstream-operator-sdk` | JVM/Python authoring SDKs and metadata validation |
| `xstream-operator-inference` | On-demand, reusable inference workers |
| `plugins/xstream-plugin-operator-executors` | Container, JVM, Python and native launch implementations |
| `plugins/xstream-plugin-execution-file` | File-backed execution history and materialized Arrow streams |
| `connectors/xstream-dacp-protocol` | Remote data wire encoding and TLS client transport |
| `connectors/xstream-dacp-client` | Remote catalog, Item and Job client used by the runner and designer |
| `xstream-engine` | Embedded execution and `xstream-runner` |
| `xstream-designer` | Visual authoring and previews |

Use JDK 17 and sbt 1.10.x. Run `scripts/bootstrap-local.sh` to publish development dependencies, then `sbt test`. Stage applications with `(cd xstream-engine && sbt stage)` and `(cd xstream-designer && sbt stage)`. Artifacts use organization `io.github.rdcn-link` and version `0.1.0-SNAPSHOT`.

Data contract tests live in `xstream-data/xstream-data-tests`. Designer browser tests in `scripts/test-designer-*.py` require Playwright and Chromium. Run `python3 scripts/check-doc-translations.py` to check translation pairs. Third-party format dependencies belong to plugins; see [plugin development](plugins.md).
