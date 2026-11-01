# 算子流

[English](operator-streams.md)

每个端口消费或产生一条有序 Arrow 流，带有明确的 schema、字典和记录批次。Bytes 使用二进制块，records 保留表结构，数组及序列视图保留科学数据结构。表示方式和缓冲区所有权见 [Arrow 契约](arrow-streams_zh.md)。

源算子声明 `inputs: {}`，汇算子可以没有输出。Job 使用 `step[:port]` 连接端口，默认端口为 `result`。每个输出最多有一个消费者。外部 Item 通过 `read_item` 进入；Dataset 是命名空间，不是隐含的端口输入。多个输入必须按端口名的字典序读至结束，需要时可以在消费前检查其 schema。

步骤通过有界缓冲和背压并发执行。同进程执行单元直接传递借用的 Arrow 批次；跨进程和网络边界序列化为 Arrow IPC。借用缓冲区必须在前进前消费或复制。字节分块不要求完整 Item 驻留内存。有界文档算子会明确说明整体收集上限。

算子 SDK 处理输入输出帧、成功结束和诊断 stderr。失败、取消或超时会结束当前尝试并关闭资源。失败尝试已经输出的数据属于部分结果，必须丢弃。结果流只消费一次；通过[物化与重放](materialization_zh.md)显式保留选定边界。输出端口名不会分配 Dataset 名称或文件。

Runner 将选定的 records 输出打印为 JSONL，将字节流直接写到 stdout。嵌入应用逐步消费 `OperatorFrame`，并负责取消和清理。[远程连接器](../connectors/xstream-dacp-client/README_zh.md)支持目录浏览、Item 流和远程 Job 结果流。网络集成是可选能力，本地执行不需要网络服务。

参见 [Job YAML](jobs_zh.md)、[算子 SDK](../xstream-operator-sdk/README_zh.md)和[算子编写](operator-authoring_zh.md)。
