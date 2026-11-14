# Scala Source and Sink examples

[中文](README_zh.md)

Four small operators demonstrate graph roles independently of implementation type. All four use `type: sdk` and the Scala JVM SDK. They process Arrow-backed bytes streams in bounded chunks; no file is written to the server and no external service is contacted.
Each implementation uses the base `Operator` interface; the published manifest declares its Source or Sink role and port contract. The dedicated `SourceOperator` and `SinkOperator` interfaces are optional role markers.

| Operator | Role | Behavior |
|---|---|---|
| `text_source@1.0` | Source | Emits UTF-8 `text` repeated `repeat` times, with an 8 MiB output bound |
| `range_source@1.0` | Source | Emits `count` signed integers from `start`, one per line; at most 100,000 values |
| `contains_text_sink@1.0` | Sink | Consumes bytes and fails the Job unless `text` occurs, including across chunk boundaries |
| `min_lines_sink@1.0` | Sink | Consumes bytes and fails the Job unless at least `minimum` lines occur |

Source steps have no inputs. Sink steps consume an input and have no output port. For example:

```yaml
name: Check generated lines
steps:
- id: generate
  operator: range_source@1.0
  inputs: {}
  params: {start: 1, count: 100}
- id: check
  operator: min_lines_sink@1.0
  inputs: {source: generate}
  params: {minimum: 100}
```

To return a Source stream to a caller, connect it to `client_sink@1.0` instead of a validation Sink. Source and Sink examples do not replace `item_source@1.0`, which reads catalog Items, or dataset publication, which persists data products.

Run contract tests with `sbt test` in this directory. Submit and approve on a **local** repository with:

```bash
python3 publish.py --url http://127.0.0.1:8820 \
  --token-file /secure/submitter.token \
  --approve-password-file /secure/admin-password
```

Omit `--approve-password-file` to leave submissions pending for administrator review. The script packages only Scala source, `pom.xml`, documentation and license; the repository builds the thin JAR. Runtime `jvm-arrow@2.1` must already be registered. See [operator development](../../../docs/operator-authoring.md) and [submission](../../../docs/operator-submission.md).
