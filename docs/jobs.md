# Streaming jobs

[中文](jobs_zh.md)

A Job is a YAML document describing processing steps and result streams. Each step uses `inputs` to reference upstream output ports; these stream connections determine execution dependencies.

```yaml
name: weather-filter
steps:
  - id: read
    title: Read weather observations
    operator: item_source@1.0
    inputs: {}
    params:
      uri: context:/weather/observations
      view: records
  - id: copy
    title: Filter valid observations
    operator: piflow_filter@2.0
    inputs:
      source: read
    params:
      condition: "quality = '0'"
  - id: client_sink
    operator: client_sink@1.0
    inputs:
      result: copy
```

## Fields and stream connections

`name` is the required readable workflow name, separate from the generated execution ID. It must contain 1–256 characters, cannot be blank, and cannot contain control characters. Each step has a unique `id`, optional descriptive `title`, versioned `operator`, named `inputs`, and optional `params`. Input bindings use `step` or `step:port`; omitted ports mean `result`. Each output has at most one consumer. Use explicit duplication operators or separate ports for fan-out.

Operators declare two independent properties: `role` (`source`, `transform`, or `sink`) describes their place in the graph; `type` (`sdk`, `command`, or `script`) describes how their implementation runs. Sources have no input ports, sinks have no output ports, and transforms connect streams between them.

`item_source@1.0` is a repository-published, zero-input transport source with output port `result`, requiring no operator container. Set `uri` to `context:/dataset/item` for the receiving JVM’s DataCatalog, or to a complete `dacp://host:port/dataset/item` URI for an explicitly addressed service. Optional `view` selects an advertised representation, including `bytes`, `records`, `array` or `sequence`; omission prefers records, then bytes, then the first available scientific view. Optional `selection` is a view-specific mapping for scientific reads. Optional `sql` queries the records view, selects records by default and rejects explicit bytes.

For a Union target, use its public dataset paths. Union resolves the owning members, places steps and dispatches node sub-jobs; authors describe only the logical stream graph.

## Structure inference and sinks

Every operator must implement [structure inference](structure-inference.md). This method returns output port names and their stream structures. A sink may infer an empty output map and finish successfully without emitting frames. Add an explicit `client_sink@1.0` step to return a stream to the caller. A Job with only other terminal sinks completes without returning result data; there is no top-level `outputs` field. Submission and execution do not invoke inference automatically; explicitly call `infer` during authoring when needed.

Upstream and downstream steps run concurrently with bounded buffering. Failure, cancellation and timeout cancel the attempt and release resources. Retries and materialization are configured in a separate [execution policy YAML](materialization.md). Retries can repeat external side effects; operators must provide appropriate idempotency.


See the [operator catalog](https://github.com/rdcn-link/xstream-codex/blob/main/docs/operator-catalog.md) for currently published collaboration operators, parameters and complete input/output examples.


An optional step `description` contains up to 8192 characters of multiline documentation. It is retained in saved and dispatched Jobs and does not affect execution. Example:

```yaml
description: |
  Read weather observations.
  Preserve the source units.
```

Standalone Jobs can define SDK, command and script operators in `operators`; see [Job-local operators](inline-operators.md). Jobs containing inline definitions run locally and cannot be submitted to DACP or Union.

## Repository stream endpoints

`item_source@1.0` and `client_sink@1.0` require approved repository manifests and verified `stream` programs. Their transport services use the bound catalog and caller result channel without executing package code inside the host JVM. Missing packages fail explicitly. See the [programs and publication example](../examples/operators/stream-endpoints/README.md).
