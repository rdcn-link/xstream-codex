# Native command operators

Implemented: `executor: command` adapts one bytes input to a program's stdin and its stdout to one bytes output. Programs may be compiled C, C++, Rust, Go, or existing command-line tools. Execution remains inside an isolated Docker container; no host-process executor is provided. Normal mode supports published command operators. Inline native source compilation is not supported.

## Definition

```yaml
name: uppercase
version: "1.0"
image: example/native-runtime:1
executor: command
entrypoint: /usr/bin/tr
executor_config:
  args: [a-z, A-Z]
  stdin: source
  stdout: {port: result, representation: bytes, format: text}
inputs:
  source: {representations: [bytes], formats: []}
icon:
  path: "M3 5h18v14H3zM3 10h18M9 5v14"
  color: "#14866b"
parameters:
  type: object
  properties: {}
  required: []
  additionalProperties: false
```

The runtime must provide `/usr/local/bin/python`, PyArrow, the executable, and its OS/architecture-compatible dependencies. The bridge reuses the existing Python runtime; it does not require Python or Arrow dependencies in the native program. `xstream/operator-python:1.0` can run the [C artifact example](../examples/operators/native/README.md), but does not necessarily contain tools such as `tr`. Install required tools in a derived image before publishing.

`entrypoint` is an absolute executable path. `args` is a list of literal strings, passed directly without shell interpretation, expansion, or substitution of job parameters. All three settings `args`, `stdin`, and `stdout` are required; unknown keys fail. Declare the stdin port in `inputs` with only the `bytes` representation. Set `stdin: null` for a source operator or `stdout: null` for a sink. Do not declare `outputs` in the manifest: the mandatory executor inference method derives it from `executor_config.stdout`. A non-null stdout requires `port`, `representation: bytes`, and a nonempty `format`. Formats label content; the adapter does not convert it.

To publish a separate executable artifact, set `artifact: path/to/program` and `entrypoint: /operator/program`, matching the artifact filename. Paths in local definitions are relative to `operator.yaml`. The downloaded artifact is verified and mounted read-only. Because content-addressed storage does not preserve executable permission bits, the bridge copies it into private container `/operator-work`, sets executable permissions, and launches that copy. The plugin requests a bounded 512 MiB executable tmpfs for this copy; ordinary `/tmp` stays non-executable. Shared libraries must already be in the runtime. There is no dependency download or native compilation during execution.

## Stream behavior

The adapter unwraps Arrow IPC byte cells from the named input FIFO, writes their bytes to stdin, and closes stdin after input ends. Concurrently it reads stdout in chunks of at most 64 KiB and emits XStream `start/data/end` frames. Bounded pipes propagate backpressure; the complete input or output is never collected. Stderr remains diagnostics and does not enter the output stream.

Output `end` is emitted only after successful program exit and successful input delivery. A nonzero exit or broken input delivery fails the job; previously delivered data is partial. Cancellation or timeout uses the engine's container lifecycle, terminating the program and its child processes. Commands should consume their complete stdin; early stdin closure is not a successful truncation/preview mechanism. Use shell preview flags to cancel a job intentionally.

The adapter supports zero or one bytes input and zero or one bytes output. Records serialization, multiple pipes, filename arguments, seekable temporary input files, and shell pipelines are not implemented. Use a custom operator wrapper for these cases; do not implicitly flatten structured or multidimensional data.

## Publication and execution

The operator repository accepts source-only submissions through `xstream-operator submit`, then builds and reviews them. Native programs require an administrator-provided build runtime; the published package and shared execution runtime are downloaded separately. See [submission and review](operator-submission.md). Local development can use the `command` builder in [local operator libraries](local-operators.md).

Jobs reference native operators with ordinary input bindings, without special step syntax. Run them using `xstream-runner engine.yaml job.yaml`.

## Plugin contract and verification

`CommandExecutor` registers in `META-INF/services/xstream.execution.OperatorExecutor` and ships in `plugins/xstream-plugin-operator-executors`. External plugins compile against `xstream-execution-api` as Provided, package their service files and install their JAR/private dependencies in the host's `plugin_directory`. Restart the host after installation. Plugins validate their own settings; the engine manages resources.

```bash
(cd plugins/xstream-plugin-operator-executors && sbt 'testOnly xstream.operator.executors.CommandExecutorSpec')
```

Contract tests cover registration, literal arguments, supported ports, configuration and launch rules. See the [native example](../examples/operators/native/README.md).
