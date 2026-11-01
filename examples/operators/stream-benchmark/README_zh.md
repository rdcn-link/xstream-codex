# Streaming benchmark operators

[English](README.md)

Scala `ByteStreamOperator` 每次读取至多 64 KiB 字节，进行 ASCII 大小写转换，不收集完整 Item。

```bash
(cd examples/operators/stream-benchmark && sbt package)
```

通过相同数据和算子 Job，对比引擎配置 `operator_fusion_enabled: true` 与 `false`。消费完整输出并校验字节数和摘要后再记录耗时；分别预热，交替测量并重复，确保运行时镜像、资源限制和数据规模一致。配置和任务可参考[引擎指南](../../../docs/xstream_zh.md)。已有测量及其测试条件见[测量报告](../../../docs/operator-fusion-benchmark_zh.md)。
