# Scala streaming operators

[中文](README_zh.md)

The six primary examples implement the JVM operator SDK in Scala: `piflow_csv_parser`, `piflow_csv_save`, `piflow_filter`, `piflow_execute_sql_stop`, `piflow_merge` and `piflow_join`, version `2.0`. Each implements processing, structure inference and bounded output samples. See the [catalog](../../../docs/operator-catalog.md) for parameters, ports and complete Jobs.

```bash
cd examples/operators/scala-piflow
sbt test
```

CSV parsing/writing uses Commons CSV. Filter and SQL use SQLite JDBC, with an in-memory database, memory-only temporary tables, query-only mode and an instruction budget. SQL expression output types remain unknown until processing. Merge infers a union schema by field name. Join infers keys, suffixes and outer-join nullability. Join's scalar inputs and emitted output each have a 128 MiB estimated-size cap; SQL has the same input/output caps. CSV and Merge process batches; this does not make SQL or Join constant-memory algorithms. Dictionary columns must be decoded before Merge; SQL/Join/CSV output accept scalar, non-dictionary columns.

`pom.xml` declares pinned third-party runtime dependencies. The repository resolves and locks those libraries in a shared dependency environment; operator JARs contain business classes only. Scala, Arrow and the SDK come from `jvm-arrow@2.1`. This Runtime also preinstalls SQLite's matching native library because execution scratch space is non-executable. No package installation occurs during Job execution. Operators with the same compatible runtime and dependency environment are eligible for one process, including across different operator packages.

Build and register the execution/compiler Runtime as described in [build-runtimes.sh](build-runtimes.sh). Source publication uses the repository's normal source-build API and administrator review:

```bash
python3 publish.py --url http://localhost:8820 --token-file /secure/publisher.token csv_parser
```

Repeat for each operator, or omit names to submit all six. `--approve-password-file` additionally waits for each build and reviews it before proceeding; it is intended for the administrator maintaining a reviewed example deployment. Credentials are never included in the source archive. Published sources include the Apache-2.0 license and upstream attribution.
