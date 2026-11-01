# Streaming benchmark operators

[中文](README_zh.md)

Scala `ByteStreamOperator` converts ASCII case in chunks of at most 64 KiB, without collecting an entire Item.

```bash
(cd examples/operators/stream-benchmark && sbt package)
```

Compare the same data and operator Job using `operator_fusion_enabled: true` and `false`. Drain and validate the entire output before recording elapsed time. Warm up each strategy, alternate repeated measurements, and keep runtime images, resource limits and data size equal. See the [engine guide](../../../docs/xstream.md) for configuration and Jobs, and the [measurement report](../../../docs/operator-fusion-benchmark.md) for recorded results and conditions.
