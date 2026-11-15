# 开发具有暂存生命周期的 Sink 算子

[English](sink-lifecycle.md)

Sink 消费流，不提供输出端口。`process` 必须将全部输入读到 Arrow EOS，只暂存结果，不对外发布。某个输入或 Sink 步骤完成，不代表可以发布。

## Scala SDK

```scala
import xstream.operator.{SinkOperator, OperatorContext}
import xstream.execution._
import com.fasterxml.jackson.databind.JsonNode

object ValidateSink extends SinkOperator {
  def inferOutputs(in: Map[String, StreamDescriptor], p: JsonNode) =
    InferenceResult(Map.empty)
  def previewOutputs(in: Map[String, StreamDescriptor], p: JsonNode,
                     limits: PreviewLimits): Map[String, OutputSample] = Map.empty

  def process(ctx: OperatorContext): Unit = {
    var count = 0L
    ctx.input("source").foreachBytes(chunk => count += chunk.length)
    require(count > 0, "Input must not be empty")
  }
  def onComplete(ctx: OperatorContext): Unit = () // Validation has no external effect.
  def onDiscard(ctx: OperatorContext, cause: Throwable): Unit = ()
}
```

发布型 Sink 应把临时文件写入 `ctx.sinkWorkspace`，将外部发布放到 `onComplete`。可用 `ctx.jobId` / `ctx.attemptId` 标识外部暂存对象；这些是标识符，不是凭据。工作区只在本次隔离进程中存在，受 `/tmp` 资源上限约束。外部存储 Sink 仍需自行实现经过授权的存储集成；生命周期机制不增加网络权限、宿主挂载或凭据。

`onDiscard` 只能清理本次尝试的暂存对象，应能容忍重复请求，不能影响以前成功发布的结果。回调必须有界；外部操作可能结果不确定时，需要幂等设计。引擎确认清理成功后才重试；任意 Sink 开始提交后，都不再自动重试。

## Python SDK

使用 `xstream_operator.sink.run_sink(process, on_complete, on_discard)`。函数签名分别为 `process(ctx)`、`on_complete(ctx)`、`on_discard(ctx,error)`。输入沿用 `OperatorContext` API，`ctx.job_id` 和 `ctx.attempt_id` 标识本次尝试。请通过 context 消费输入以配合取消。结构推导仍使用独立的 `run_inference` 入口，返回空 outputs 映射。打包或重建运行时镜像时应包含匹配的 SDK。

## Container 与自定义启动实现

声明 `sink` 角色的自定义运行时必须实现生命周期协议；进程退出码 0 本身不代表完成提交。宿主提供 `XSTREAM_SINK_SOCKET` 指定的私有 Unix socket，以及 `XSTREAM_JOB_ID`、`XSTREAM_ATTEMPT_ID`。消息为 UTF-8 JSON 对象，前置四字节大端长度，最多 8192 字节。

1. 暂存前连接 socket，发送 `{"state":"ready"}`。
2. 消费全部输入并完成暂存，输出普通算子帧 `{"type":"sink_prepared","port":""}`，payload 为空，保持进程存活。
3. 从 socket 接收 `{"state":"complete"}` 或 `{"state":"discard","message":"..."}`。
4. 执行回调后回复 `{"state":"completed"}` 或 `{"state":"discarded"}`；失败则回复 `commit_failed` 或 `discard_failed`，并使进程失败。
5. 释放资源并退出。提交成功需要确认消息及进程退出码 0。

丢弃决定可能在处理过程中到达；应协作停止处理、清理暂存，然后确认。SDK 会处理这些消息，业务实现使用回调即可。立即写入外部副作用的普通命令或脚本不属于安全的暂存型 Sink；应提供支持生命周期的适配器，或将计算数据作为 Transform 输出，通过 `client_sink` 交给应用程序接收。

## Union 与失败结果

Worker 处理完成后报告 `ready_to_commit`，保留 Sink 运行时。Union 等待全部 Worker 和最终结果流完成后，再通过绑定证书的内部 `complete_execution` 操作决定提交。多级 Union 将提交决定交给上级。取消沿用现有授权取消操作；重试前必须确认清理完成、旧工作线程停止。

提交失败可能留下部分已发布结果或不确定结果。`commit_started` 和 Sink 状态标识这一边界；不承诺分布式原子事务或崩溃恢复。测试应覆盖暂存后的下游失败、提交前取消、清理失败、部分提交、幂等清理、结果接收器失败以及运行时资源释放。
