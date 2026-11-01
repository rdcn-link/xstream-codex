# Streaming jobs

[中文](jobs_zh.md)

A Job is a YAML document describing processing steps and result streams. Each step uses `inputs` to reference upstream output ports; these stream connections determine execution dependencies.

```yaml
name: weather-filter
steps:
  - id: read
    title: Read weather observations
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/weather/observations
      view: records
  - id: copy
    title: Filter valid observations
    operator: piflow_filter@1.0
    inputs:
      source: read
    params:
      condition: "quality = '0'"
outputs:
  result: copy
```

## Fields and stream connections

`name` is the required readable workflow name, separate from the generated execution ID. It must contain 1–256 characters, cannot be blank, and cannot contain control characters. Each step has a unique `id`, optional descriptive `title`, versioned `operator`, named `inputs`, and optional `params`. Input values and Job outputs use `step` or `step:port`; omitted ports mean `result`. Each output has at most one consumer. Use explicit duplication operators or separate ports for fan-out.

`read_item@1.0` is a built-in, zero-input transport source with output port `result`, requiring no operator container. Set `uri` to `context:/dataset/item` for the receiving JVM’s DataCatalog, or to a complete `dacp://host:port/dataset/item` URI for an explicitly addressed service. Optional `view` selects an advertised representation, including `bytes`, `records`, `array`, `image` or `sequence`; omission prefers records, then bytes, then the first available scientific view. Optional `selection` is a view-specific mapping for scientific reads. Optional `sql` queries the records view, selects records by default and rejects explicit bytes.

For a Union target, use its public dataset paths. Union resolves the owning members, places steps and dispatches node sub-jobs; authors describe only the logical stream graph.

## Structure inference and sinks

Every operator must implement [structure inference](https://github.com/rdcn-link/xstream-codex/blob/main/docs/structure-inference.md). This method returns output port names and their stream structures. A sink may infer an empty output map and finish successfully without emitting frames. A Job may set `outputs: {}`; the client then waits for completion without receiving result data. Unselected branches still execute. Submission and execution do not invoke inference automatically; explicitly call `infer` during authoring when needed.

Upstream and downstream steps run concurrently with bounded buffering. Failure, cancellation and timeout cancel the attempt and release resources. Retries and materialization are configured in a separate [execution policy YAML](materialization.md). Retries can repeat external side effects; operators must provide appropriate idempotency.


See the [operator catalog](https://github.com/rdcn-link/xstream-codex/blob/main/docs/operator-catalog.md) for currently published collaboration operators, parameters and complete input/output examples.


An optional step `description` contains up to 8192 characters of multiline documentation. It is retained in saved and dispatched Jobs and does not affect execution. Example:

```yaml
description: |
  Read weather observations.
  Preserve the source units.
```
