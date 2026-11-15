# Job-local operators

[中文](inline-operators_zh.md)

A local XStream Job can define operators directly in its `operators` mapping. A step selects one with `operator: {inline: name}`. Supported adapters are discovered through `xstream.execution.InlineOperatorProvider`; the bundled `sdk`, `command`, and `script` providers live in `xstream-plugin-operator-executors`. Every definition is validated, including unused entries. No operator library directory or repository submission is needed for local development.

Use this form when a Job needs a program defined alongside its steps. Choose `sdk` for an existing XStream SDK program, `command` for a command-line tool, or `script` for source embedded in the Job or stored in a local file. These implementation types are independent of the graph role (`source`, `transform`, or `sink`). The [operator development guide](development.md) covers the same choices for reusable operators.

## Configure the runtime

The standalone engine config names preinstalled container images. The SDK executor is selected by `executor`; command and script definitions use the built-in `command` bridge. Images for the latter two must include `/usr/local/bin/python`, PyArrow and the selected tool/interpreter. Programs run in isolated containers with the configured memory and temporary-space limits.

```yaml
inline_runtimes:
  python_tools:
    image: xstream/operator-python:1.0
    executor: python
  jvm_tools:
    image: xstream/operator-jvm:1.0
    executor: jvm
```

Runtime images and interpreters are administrator-selected local configuration, not per-Job host execution privileges. `job_working_directory` defaults to the directory containing the Job YAML in `xstream-runner`; the embedded engine can set it explicitly. Program paths are resolved against that directory. A referenced file is copied into private staging before execution, with a bounded size and cancellation checks. The engine removes staging after the Job. `script.source` is limited to 512 KiB.

## SDK code

An SDK definition declares a program file, its entrypoint and input/output port contracts. Scala/Java use a JAR plus a fully qualified JVM main class. Python uses `.py`, `.zip`, or `.whl`; the `entrypoint` is a module name for packages. The file must follow the existing XStream SDK process/inference protocol.

```yaml
operators:
  filter:
    type: sdk
    role: transform
    runtime: jvm_tools
    program: {path: ./target/filters.jar, entrypoint: example.FilterOperator}
    inputs:
      source: {representations: [records], formats: []}
    outputs:
      result: {view: records}
```

Dependencies belong in the configured runtime image. An SDK definition can also use `runtime: python_tools` and `program: {path: ./filter.py, entrypoint: filter}`. SDK operators can handle the declared structured streams directly. Declaring a representation does not flatten arrays or sequences.

## Existing command-line tool

The built-in adapter accepts bytes streams and can route each input to `stdin` or a temporary `file`, and each output from `stdout` or a temporary `file`. An executable already present in the image uses `command`; a local executable file can additionally use `artifact: ./tool`, copied into the container's bounded executable scratch area. Commands are launched directly, without a shell.

```yaml
operators:
  convert:
    type: command
    role: transform
    runtime: python_tools
    command: /usr/bin/gdal_translate
    args: [{param: format}, {input: source}, {output: result}]
    inputs:
      source: {view: bytes, binding: file}
    outputs:
      result: {view: bytes, binding: file, format: geotiff}
```

`args` entries are literal strings or whole-value references `{param: name}`, `{input: port}`, `{output: port}`. Parameter values must be scalar. Every file port must be referenced in `args`; generated file paths cannot be chosen by the Job. The number of ports is limited to 32 per direction and at most one stdin and one stdout. File bindings materialize in the container's quota-controlled `/tmp` and are cleaned after execution. The Arrow port still streams bytes between steps. Nonzero process exit or an absent output file fails the Job. For records, arrays, multiple scientific views or custom previews, use an SDK operator.

## Existing script

A script definition uses the same I/O and argument declarations. Set `script.path` to an existing local file or `script.source` to embedded source text; choose exactly one. The interpreter is an absolute path inside the runtime image. The script itself need not import the XStream SDK.

```yaml
operators:
  clean:
    type: script
    role: transform
    runtime: python_tools
    interpreter: /usr/local/bin/python
    script: {path: ./clean.py}
    args: [{input: source}, {output: result}, {param: threshold}]
    inputs:
      source: {view: bytes, binding: file}
    outputs:
      result: {view: bytes, binding: file, format: csv}
```

Use `stdin`/`stdout` bindings for stream-compatible scripts. The adapter never interpolates a shell command. Cancellation terminates the container and its process; stdout is reserved for declared output bytes, with diagnostics on stderr.

## Run and publish

```yaml
name: Local pipeline
operators:
  upper:
    type: script
    role: transform
    runtime: python_tools
    interpreter: /usr/local/bin/python
    script:
      source: |
        import sys
        sys.stdout.buffer.write(sys.stdin.buffer.read().upper())
    inputs:
      source: {view: bytes, binding: stdin}
    outputs:
      result: {view: bytes, binding: stdout, format: text}
steps:
  - id: read
    operator: item_source@1.0
    inputs: {}
    params: {uri: 'context:/files/input.txt', view: bytes}
  - id: upper
    operator: {inline: upper}
    inputs: {source: read:result}
    params: {}
  - id: client_sink
    operator: client_sink@1.0
    inputs: {result: upper:result}
```

Run with `xstream-runner engine.yaml job.yaml`. To submit remotely, publish each custom operator to the public repository and replace inline references with approved `name@version` references. DACP nodes and Union reject **any** Job containing `operators`, even when entries are unused; they also reject local directory and legacy source references. Scala/Python clients reject these Jobs before connecting, and servers validate again. The Designer preserves inline definitions in YAML and supports local schema inference; select a local Target to edit them.

For provider registration, add a no-argument `InlineOperatorProvider` implementation and its class name in `META-INF/services/xstream.execution.InlineOperatorProvider`. Implement `validate` and `prepare`; the plugin owns its definition fields, while the engine owns stage cleanup, container isolation and cancellation. Verify with `sbt 'testOnly xstream.operator.executors.InlineOperatorsSpec'` from `plugins/xstream-plugin-operator-executors` and `sudo -n python3 scripts/test-inline-operators.py` with the staged runner and Docker access. To include a Scala SDK JAR in the smoke test, pass `--jvm-jar path/to/operators.jar --jvm-entrypoint your.package.Uppercase`; the entrypoint must read a bytes `source` port and write an uppercase bytes `result` port.
