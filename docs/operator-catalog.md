# Operator catalog for collaboration examples

[中文](operator-catalog_zh.md)

The collaboration-example deployment provides these six operators at recommended version `2.0`, implemented in Scala with the `jvm` executor. Each source package implements processing, structure inference and bounded output samples. Scala, Arrow and the XStream SDK come from `jvm-arrow@2.1`; `pom.xml` declares Commons CSV and SQLite JDBC, which the repository locks into a shared dependency environment using `dependencies.provider: jvm`. Operator JARs contain business code only. This describes the example deployment, not operators automatically installed in new repositories.

```bash
curl http://localhost:8820/v1/operators
```

This endpoint and the repository homepage show the versions actually published. `read_item@1.0` is a built-in XStream data-reading step and requires no repository publication. Java, Python, script, native-command, common-operator collections and other PiFlow adaptations in the source tree are buildable examples, not necessarily published operators.

## Ports, parameters and execution

All operators below use the output port `result`. Output descriptions come from `inferOutputs(inputs, params)`; manifests declare inputs only. Job execution does not call inference automatically.

| Operator | Input → output | Example parameters | Execution and limitations |
|---|---|---|---|
| `piflow_csv_parser@2.0` | `source: bytes` → `records` | `{"header":true,"delimiter":","}` | Parses UTF-8 CSV in batches; all columns are strings, without automatic type inference. Headerless example: `{"header":false,"schema":"station_id,temperature,quality"}` |
| `piflow_filter@2.0` | `source: records` → `records` | `{"condition":"CAST(temperature AS REAL) >= 30"}` | Filters each batch using a SQLite predicate; preserves the input schema |
| `piflow_execute_sql_stop@2.0` | `source: records` → `records` | `{"viewName":"observations","sql":"SELECT station_id, COUNT(*) AS n FROM observations GROUP BY station_id"}` | Collects input before executing SQLite SQL; input and output are bounded, not a constant-memory streaming algorithm |
| `piflow_merge@2.0` | `left: records`, `right: records` → `records` | `{}` | Emits left followed by right; merges fields by name, appends right-only fields and fills absent values with null. Rejects incompatible types; does not deduplicate or sort |
| `piflow_join@2.0` | `left: records`, `right: records` → `records` | `{"joinMode":"inner","correlationColumn":"station_id"}` | Collects both inputs before joining. Modes: `inner`, `left`, `right`, `full_outer`. Duplicate right-side non-key columns receive `_right`; remaining ambiguity is rejected |
| `piflow_csv_save@2.0` | `source: records` → `bytes` (CSV) | `{"header":true,"delimiter":","}` | Emits a CSV byte stream; **does not save a CSV file on the server**. Scalar columns only |

Merge requires decoded dictionary columns. Join and SQL accept scalar columns; CSV output also requires scalar columns. CSV parser can infer string columns from a declared headerless schema or an upstream byte sample. Join infers key fields, suffixes and outer-join nullability. SQL expression output types can depend on data and remain unknown before processing.

SQL and Join input collection is limited to 128 MiB using estimated memory usage; actual memory also includes Arrow, JVM objects and algorithm overhead. SQLite SQL is not Spark SQL and does not support arbitrary Spark functions. See the [operator authoring guide](operator-authoring.md), [specs.json](../examples/operators/piflow/specs.json) and [Scala operator sources](../examples/operators/scala-piflow/) for the contracts.

## Input and output examples

CSV input:

```csv
station_id,temperature,quality
S001,32.5,0
S002,18.0,0
S003,35.0,1
```

CSV parser produces records with three string columns. Apply Filter with:

```json
{"condition":"quality = '0' AND CAST(temperature AS REAL) >= 30"}
```

The output retains the three string columns and only the `S001` row. CSV save produces:

```csv
station_id,temperature,quality
S001,32.5,0
```

For numeric columns, explicitly cast in SQL, for example `SELECT station_id, CAST(temperature AS REAL) AS temperature FROM observations`. Do not assume that CSV parser converts types automatically.

## Shell usage

After connecting and selecting a weather dataset containing `/observations`:

```text
run piflow_csv_parser@2.0 --input source=/observations --view bytes
```

If the Item already offers a records view, filter it directly:

```text
run piflow_filter@2.0 --input source=/observations --view records --params '{"condition":"CAST(temperature AS REAL) >= 30"}'
```

Use this Job to combine parsing, filtering and CSV output. The node example URI is `dacp://localhost:18861/weather/observations`. For Union submission, replace it with the Union public URI returned by the actual catalog, not a member-internal URI. Union plans the execution placement.

```yaml
name: High-temperature observations
steps:
- id: read
  operator: read_item@1.0
  inputs: {}
  params:
    uri: $INPUT_URI
    view: bytes
- id: parse
  operator: piflow_csv_parser@2.0
  inputs:
    source: read
- id: filter
  operator: piflow_filter@2.0
  inputs:
    source: parse
  params:
    condition: "quality = '0' AND CAST(temperature AS REAL) >= 30"
- id: export
  operator: piflow_csv_save@2.0
  inputs:
    source: filter
outputs:
  result: export
```

Save as `weather.yaml`, and create `variables.yaml`:

```json
{"INPUT_URI":"dacp://localhost:18861/weather/observations"}
```

```text
submit weather.yaml --vars variables.yaml
```

Results stream to the client by default. Materialization at selected boundaries, retries and timeouts use a separate [execution policy](materialization.md). Operator-internal input collection and engine-managed output persistence are separate mechanisms.

## Publication and verification

Browsing and downloads are public; publication requires a token. Build, test and publish operators individually using the [operator publication guide](operator-submission.md); see the [repository manual](https://github.com/rdcn-link/xstream-operator-repository) for the HTTP upload contract. After publication, check `/v1/operators`, then the version page, parameters, sources and license.

## Script operators

The example repository also provides `script_text_replace@1.0`, `script_line_number@1.0`, `script_jsonl_project@1.0` and `script_records_to_jsonl@1.0`. They use `python-arrow@2.0` and the same locked dependency environment, with no bundled SDK or external commands. See [script examples](../examples/operators/scripts/README.md) for parameters, ports, limits and a complete Job.
