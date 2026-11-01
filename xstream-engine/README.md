# XStream Engine

Standalone and embeddable Scala execution of streaming YAML Jobs. One JVM shares one **DataCatalog → Dataset → Item** hierarchy. Embedding applications share their DataCatalog directly; local execution needs no server.

Requires JDK 17 and sbt. From the repository root:

```bash
cd xstream-engine
sbt test stage
cd ..
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/xstream/engine.yaml examples/xstream/read.yaml
```

`xstream-runner` is a program shipped by this module, not a separate module. Records are printed as JSONL; bytes are streamed directly to stdout. The launcher accepts `CONFIG.yaml JOB.yaml [VARIABLES.yaml] [POLICY.yaml]`.

Use `read_item.params.uri: context:/dataset/item` for the bound DataCatalog, or an explicit `dacp://host:port/dataset/item` URI. Configure remote connections and credentials in `nodes`. Operators come from approved public repositories or explicit [local source directories](../docs/local-operators.md); isolated runtimes and compatible-operator fusion are supported.

See [the full guide](../docs/xstream.md) for Scala embedding, plugin registration, cancellation, materialization/replay, catalog lifecycle and designer configuration. [中文](README_zh.md).

## Components and scope

- `xstream-engine`: the embeddable execution engine and `xstream-runner` launcher.
- [xstream-designer](../xstream-designer/README.md): visual YAML Job authoring, local and remote catalog browsing, schema inference and bounded samples.

Local execution reads Items directly from the JVM catalog. Explicit remote URIs use configured data connectors and feed local steps. The DACP client provides remote Job submission; Union provides automatic compute placement. Stream connections do not require intermediate files. Materialization and replay are explicit execution-policy choices; operator packages, runtimes and execution history may still use disk.

The example configures an operator repository at `http://localhost:8820`; its built-in `read_item` step needs no repository download. Jobs using repository operators require the configured repository or a verified local cache. Run the launcher from the repository root so its relative data and storage paths resolve correctly. It selects exactly one Job output and writes that stream to stdout. The embedded API supports multiple outputs.

See [example configuration](../examples/xstream/engine.yaml), [example Job](../examples/xstream/read.yaml), and [Job syntax](../docs/jobs.md).


Execution history records the actual execution units selected by the engine. Execution units include the runtime model/version and dependency versions supplied by the repository catalog for the pinned image. Shared-process units list their member steps and runtime image; separate steps and host stream sources are recorded separately. Viewers must not infer fusion from matching runtime images.

[Local execution and remote data](../docs/execution-targets.md).
