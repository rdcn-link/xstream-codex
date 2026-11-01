# Operator streams

[中文](operator-streams_zh.md)

Each port consumes or produces one ordered Arrow stream, with an explicit schema, dictionaries and record batches. Bytes use binary chunks; records retain tabular schemas; array and sequence views preserve scientific structures. See [Arrow contracts](arrow-streams.md) for representations and buffer ownership.

A source declares `inputs: {}`. A sink may declare no outputs. Jobs connect ports with `step[:port]`, defaulting to `result`. An output has at most one consumer. External Items enter through `read_item`; a Dataset is a namespace, not an implicit port input. Read multiple inputs in lexical port-name order through end-of-stream. Inspect their schemas before consuming them when needed.

Steps run concurrently with bounded buffers and backpressure. Same-process execution units pass borrowed Arrow batches directly; process and network boundaries serialize Arrow IPC. Operators must consume or copy borrowed buffers before advancing. Byte chunks do not require a complete Item in memory. Bounded document operators explicitly declare their collection limits.

Operator SDKs handle input/output framing, successful termination and diagnostic stderr. Failure, cancellation or timeout terminates the attempt and closes resources. Already emitted results from a failed attempt are partial and must be discarded. A result stream is consumed once; [materialization and replay](materialization.md) explicitly retain selected boundaries. Output port names do not allocate Dataset names or files.

The runner prints its selected output as JSONL for records or raw bytes for byte streams. Embedded applications consume `OperatorFrame` incrementally and own cancellation and cleanup. The [remote connector](../connectors/xstream-dacp-client/README.md) supports catalog browsing, Item streams and remote Job result streams. Network integrations are optional and not required for local execution.

See [Job YAML](jobs.md), [operator SDK](../xstream-operator-sdk/README.md), and [operator authoring](operator-authoring.md).
