# Dependency environments and execution units

[中文](dependency-environments_zh.md)

A **Runtime** supplies a pinned operating-system image, interpreter/JDK and shared SDK. A **dependency environment** adds locked third-party libraries to that Runtime. An **execution unit** runs one or more steps on one node. Sharing an environment does not imply that independent executables become one process.

## Declare dependencies

Python projects put `pyproject.toml` beside `operator.yaml`:

```toml
[project]
name = "weather-filter"
version = "1.0.0"
requires-python = ">=3.11,<3.14"
dependencies = ["packaging==25.0"]
```

```yaml
# operator.yaml: remaining operator metadata and mandatory inference/sample functions are unchanged.
dependencies:
  provider: python
```

The Python provider runs uv in the registered build image, produces a hash-locked transitive `requirements.lock`, and installs the selected packages into a separate payload. The build image must provide `/usr/local/bin/python` and `/usr/local/bin/uv`; use the Python Runtime Dockerfile as a starting point. The runtime interpreter and builder must target the same Python ABI/platform. No package installation occurs during Job execution. For a dependency-free script use `dependencies = []`; a compiled binary is not required.

For JVM code:

```yaml
dependencies:
  provider: jvm
```

The provider reads explicit-version dependencies from `pom.xml`, or `dependencies.json` exported by a Maven, Gradle or sbt build. The exported file contains runtime Maven coordinates:

```json
["org.apache.commons:commons-csv:1.14.1"]
```

It can alternatively receive this array as `dependencies.coordinates`. Coursier (`/usr/local/bin/cs` in the registered build image) resolves transitive dependencies; the lock records every downloaded JAR's path and SHA-256. Property-based/managed Maven versions and complex scope configurations must be exported to `dependencies.json`; the provider does not interpret arbitrary Gradle/sbt programs. Arrow and XStream SDK dependencies are supplied by the Runtime and cannot be overridden as direct dependency coordinates.

The repository compiles `src/` with its registered language recipe and the resolved JARs. Local projects may use their own build tool through `local.yaml`'s command builder. The `jvm` local builder compiles simple Java `src/` projects inside the selected JDK image; it is useful for dependency-free templates. Projects with a full Maven/Gradle/sbt build use the command builder and export their runtime coordinates for environment preparation.

Native libraries and commands can be bundled in `lib/` and `bin/`. With `dependencies.provider: native`, these files are installed under `/opt/xstream/lib` and `/opt/xstream/bin`. Python/JVM providers also include these directories. They remain platform-specific; bundling libraries does not automatically fix incompatible glibc, absolute RPATHs or missing drivers. Symbolic links and unsafe paths are rejected. The Runtime must supply any interpreter used by a bundled script.

## Create, check, build and submit

```bash
xstream-operator init ./weather-filter --language python
xstream-operator check ./weather-filter
xstream-operator build ./weather-filter --config ./engine.yaml
xstream-operator submit ./weather-filter --url https://repo.example.org --token-file ./publisher.token
```

Templates support `python`, `java` and `shell`. The Shell template contains a Python SDK wrapper around a small shell fragment; the chosen Runtime must include `/bin/sh`. It is an example of a stateless chunk transform, not a generic adapter for commands requiring a complete stream. Such commands implement `process(ctx)` with bounded stdin/stdout pumping. The wrapper provides the same mandatory inference and bounded sample interfaces as other operators.

`build` invokes `xstream-runner build-operator CONFIG.yaml DIRECTORY`. This is a trusted local action; remote Jobs cannot request arbitrary local source builds. Local settings may set `dependency_builder_image` separately from `image` when resolution tools are intentionally absent from the minimal execution Runtime. Choose a registered Runtime supporting the language before submission; the template's runtime names are editable examples.

Source archives include `src/`, optional `resources/`, `bin/`, `lib/` and dependency declaration files. They exclude `local.yaml`, credentials, build outputs and caches. Inspect everything intentionally placed in the publishable directories before submitting.

## Third-party binary wrappers

The repository accepts source submissions and binary-wrapper submissions. The latter always includes wrapper source implementing the operator interfaces. Example operator fields:

```yaml
submission: binary
binary:
  artifact: vendor/tool
  source_url: https://vendor.example.org/tool
  version: "2.0"
  platform: linux/amd64
  license: BSD-3-Clause
dependencies:
  provider: native
```

The CLI uploads the executable separately and replaces `binary.artifact` with its checksum/size/filename descriptor. The repository checks ownership and platform, installs it at `/opt/xstream/bin/<original-filename>`, builds the wrapper, and keeps all artifacts private until approval. Use a Python/JVM wrapper for stream-format adaptation, inference and bounded previews. A raw executable alone is not an operator.

The automated source report covers **wrapper source only** and does not assign a binary trust score. Approval requires an explicit administrator score and an explanation. The public operator page distinguishes repository-built source from a third-party binary wrapper and publishes the final administrator assessment. `source_public` controls wrapper-source disclosure; the approved executable is downloadable. License and redistribution responsibility must be considered during review.

## Locks, cache and execution planning

```mermaid
flowchart LR
  A[Source and dependency declaration] --> B[Provider: uv / Coursier / bundled files]
  B --> C[Lock: platform and artifact hashes]
  C --> D[Base Runtime + canonical lock fingerprint]
  D --> E[Immutable cached environment image]
  E --> F[Physical execution units]
```

Locks include platform, resolved dependency content and relevant loading information. Paths locate files; digests identify their contents. Reordering mapping keys does not change the fingerprint. Different base Runtime image IDs or library contents do. Published metadata carries `environment.fingerprint`, `environment.image`, `environment.archive` and `environment.lock`; the client verifies the archive and fingerprint before loading the pinned image.

Environment archives are built atomically under a cross-process lock. Leases protect environments during acquisition/use by builders. Idle archive cleanup runs during preparation and uses a 24-hour default; stale process leases are reclaimed. Docker images are not automatically deleted because other applications may use them. Execution reuses the already loaded immutable image and does not modify a shared environment.

Steps on the same node can fuse when they form a connected, convex region, share the exact locked environment and process settings, and implement the executor's in-process interface. Different operator packages can fuse. Independent branches are not merged solely because dependencies match. Mixed environments can produce several execution units. Materialization/replay/retry policies retain their execution boundaries; fusion currently applies to the default streaming policy. Python module-path switching is not used as an isolation mechanism. Native executables remain separate processes unless they provide a specific in-process API.

Physical plans expose the environment fingerprint, Runtime and process count. Boundaries report dependency/executor or policy reasons. Data between independent units continues to use bounded stream connections; environment caching does not materialize stream results.

## Extend and validate

Implement `xstream.operator.DependencyEnvironmentProvider` and register its class in:

```text
META-INF/services/xstream.operator.DependencyEnvironmentProvider
```

`resolve` runs the dependency tool and returns a deterministic transitive lock; `build` materializes exactly that lock as a pinned image plus archive. Long operations poll `check()` and clean up on failure/cancellation. Execution services discover provider JARs through their plugin environment; repository builders load providers from their application classpath. Core code does not contain a language-provider registry.

Run the contract, dependency-plugin, executor, inference and engine tests, plus repository publication tests. Optional real-container tests:

```bash
cd plugins/xstream-plugin-dependencies
XSTREAM_DEPENDENCY_DOCKER_TESTS=1 sbt test
```

They require Docker access, `xstream/operator-python:1.0`, and a matching uv-enabled image tagged `xstream/dependency-python-builder:test`. Unit tests cover canonical identity, parallel cache construction, lease eviction, corruption, cancellation, plugin discovery, path safety and execution-unit partitioning. Compatibility-based dependency union and JVM ClassLoader isolation are future enhancements; this implementation fuses only exact environments.
