# Local operator libraries

[中文](local-operators_zh.md)

Standalone XStream accepts local operator source directories as well as published repository operators. Local development needs no repository, submitter token, approval or trust score. Processing, input/output ports, parameters, inference and samples use the same operator SDK as published packages. Runtime execution still uses the existing isolated shared Docker images; this feature does not introduce execution of operator code inside the engine JVM.

## Directory and Job reference

```text
temperature-filter/
├── operator.yaml        # Portable source-submission declaration
├── local.yaml           # This machine's runtime and build configuration
├── src/                 # Python, Java or Scala source
└── build.sbt            # Optional build tool project; pom.xml is also possible
```

A step references a source directory explicitly:

```yaml
name: Local analysis
steps:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params: {uri: 'context:/weather/observations.csv', view: bytes}
  - id: filter
    operator: {local: ./temperature-filter}
    inputs: {source: read}
    params: {min_temperature: 20}
outputs: {result: filter}
```

`operator.local` and an inline source definition cannot be combined. Normal `operator: name@version` keeps its existing repository/package lookup. With `operator: {local: name@version}`, sources come from `local_operator_library/name/version/`; that directory's name and version must match `operator.yaml`.

The runner resolves directory references relative to the Job file, not the launcher's working directory. Embedded `LocalEngine` uses `job_working_directory` (default: process working directory). An explicit runner `job_working_directory` overrides the Job-file base. `local_operator_library`, `operator_cache`, data roots and other configuration paths retain their process-working-directory semantics.

## Portable declaration

`operator.yaml` uses the same fields as a source submission:

```yaml
name: temperature_filter
version: '1.0'
description: Filter observation bytes.
language: python
runtime: python-arrow@1.0
entrypoint: temperature_filter
source_public: true
inputs:
  source: {representations: [bytes], formats: []}
parameters:
  type: object
  properties:
    min_temperature: {type: number, default: 20, description: Minimum temperature.}
  required: []
  additionalProperties: false
icon:
  path: M4 4H20L14 12V20L10 18V12Z
  color: '#14866b'
```

Provide code under `src/` using `BytesOperator`, `ByteStreamOperator`, `RecordsOperator` or the low-level SDK. Sources with no inputs and sinks with no outputs are supported. Output ports are announced by execution and the inference method, never duplicated in the manifest. Templates implement inference and bounded samples. See [operator authoring](operator-authoring.md).

`runtime` names the intended repository runtime. `local.yaml` selects the corresponding locally installed image; the developer must ensure their dependencies match. Local execution does not contact a repository to verify that correspondence. Required images must already be installed. Local builds run on the host with the invoking user's permissions; execute only source projects you trust.

## Python

```yaml
# local.yaml
image: xstream/operator-python:1.0
executor: python
build:
  provider: python
```

The Python builder creates an importable ZIP from `src/`, including helper modules and package data. It does not install requirements or resolve dependencies. Dependencies and the Python SDK must be present in the selected runtime image. The code and its entrypoint are the same modules that the repository builds.

## Java and Scala

```yaml
# local.yaml
image: xstream/operator-jvm:1.1
executor: jvm
build:
  provider: command
  command: [sbt, package]
  artifact: target/scala-2.13/local-scala-identity_2.13-1.0.jar
```

Use a normal SDK-based project and a thin JAR. For Maven, set `command: [mvn, package]` and the actual JAR path in `artifact`. The command is an argument array; no shell expansion is applied. The build runs in the operator directory; the artifact must remain within that directory and is copied into an immutable cache. Build tools, their dependencies and the matching SDK must be available locally. The runtime image supplies execution dependencies. Build-tool configuration is for local compilation; repository builders use their own language recipes and registered build environments rather than invoking the submitted build command.

## Cache, cancellation and placement

Successful builds are cached under `operator_cache/local-builds/` using the source tree, declarations and local settings. Source changes create new artifacts; a failed or cancelled build cannot publish a cache entry. Standard generated directories (`target`, `build`, `dist`, `.git`, `.venv`, `__pycache__`, `.xstream-operators`, `.xstream-executions`) and the configured build artifact are excluded from the source fingerprint. Keep caches outside source trees. Source trees are limited to 16,384 files and 64 MiB and cannot contain symbolic links. External toolchain changes require deleting the relevant local-build cache.

Cancellation and Job timeout cover the build and execution. Build diagnostics go to stderr, leaving the selected result stream on stdout. Local operators remain on the current engine and are a fusion boundary; their paths and build commands are never submitted to remote nodes. Repository-only upstream regions can still be pushed down where eligible. DACP and Union remote Job submission reject local directory steps, including in DEBUG mode. This is a trusted local development capability, not a new remote arbitrary-command interface.

Trusted embeddings can request inference and samples using `LocalEngine.inferLocal(reference, request, cancel)`. It uses the same prepared artifact and reusable inference workers. Execution does not automatically invoke inference. The current designer does not expose a local-operator palette.

## Run the complete example

From the repository root, build the runner and publish/install the matching SDK as described in [bootstrap](../README.md). Ensure the Python and JVM images are installed and sbt is on PATH:

```bash
cd xstream-engine
sbt stage
cd ..
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/local/engine.yaml examples/operators/local/job.yaml
```

The example reads a local Item, uppercases bytes in Python, then transfers the stream through Scala. It requires no operator repository. Only source builds, operator artifacts, runtime resources and execution history use disk; intermediate data remains streamed.

## Publish the same sources

```bash
xstream-operator submit ./temperature-filter \
  --url https://operators.example.org --token-file /protected/submitter.token
```

The tool reads `operator.yaml` and archives only `src/` and optional `resources/`. It excludes `local.yaml`, build outputs and other local files. Existing explicit `submission.yaml --source archive.zip` submissions remain available. License/SBOM files referenced by the declaration are uploaded separately. The repository validates the runtime, scans and builds sources, then requires administrator review. Local build success does not waive publication requirements or guarantee compatibility with the chosen repository runtime.

After approval, replace `operator: {local: ./temperature-filter}` with `operator: temperature_filter@1.0`; inputs, parameters and source code stay the same.

## Add a build provider

Implement `xstream.operator.LocalOperatorBuilder` (`id`, `build(directory, settings, destination, check)`) and register the implementation in `META-INF/services/xstream.operator.LocalOperatorBuilder`. Package the provider as a plugin JAR and configure `plugin_directory`. The builder owns its configuration validation, polls `check()` during work and returns a regular artifact inside `destination`. Built-in `python` and `command` providers use this exact contract; adding another builder requires no engine registry edit. Builders are trusted host code. See [plugins](plugins.md).
