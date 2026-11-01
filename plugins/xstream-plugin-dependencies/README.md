# Dependency environment plugin

[中文](README_zh.md)

Implements dependency resolution and pinned image construction for Python (uv), JVM (Coursier), and native bin/lib bundles through `DependencyEnvironmentProvider`. Providers register in `META-INF/services/xstream.operator.DependencyEnvironmentProvider`, without a core registry change.

See the [dependency environment guide](../../docs/dependency-environments.md) for configuration, tool requirements, cache lifecycle and binary submission examples.

Run `sbt test` for discovery, coordinate validation, path safety and cancellation contracts. Set `XSTREAM_DEPENDENCY_DOCKER_TESTS=1` to enable real-container tests.
