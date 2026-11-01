# Local source operators

[中文](README_zh.md)

A repository-free Python → Scala pipeline. From the XStream repository root:

```bash
cd xstream-engine && sbt stage
cd ..
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/local/engine.yaml examples/operators/local/job.yaml
```

Requires Docker access, installed `xstream/operator-python:1.0` and `xstream/operator-jvm:1.1` images, sbt/JDK 17 and the matching locally published XStream JVM SDK. See [bootstrap](../../../README.md). The Scala example uses a provided SDK dependency and produces a thin JAR. Build diagnostics go to stderr; stdout should contain:

```text
TEMPERATURE: 21.5 C
STREAMING WEATHER OBSERVATIONS
```

`operator.yaml` is the portable declaration; `local.yaml` is the host build/runtime configuration. Python source ZIP and Scala JAR builds are cached. Both templates provide inference and bounded samples. Processing uses streams without intermediate data materialization.

To submit either example after selecting the actual repository runtime in `operator.yaml`:

```bash
xstream-operator submit examples/operators/local/python \
  --url https://operators.example.org --token-file /protected/submitter.token
```

Sources are built and reviewed by the repository. Local settings and build outputs are not uploaded. See [local operator libraries](../../../docs/local-operators.md) for named library references, Maven builds, cancellation, plugin extensions and publication requirements.
