# Developing staged Sink operators

[中文](sink-lifecycle_zh.md)

A Sink consumes streams and exposes no output ports. Its `process` implementation consumes every input to Arrow EOS and stages its result without making it public. Completion of an individual input or Sink step is not permission to publish.

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

For a publishing Sink, write staged files beneath `ctx.sinkWorkspace` and keep external publication inside `onComplete`. Use `ctx.jobId` / `ctx.attemptId` as external staging identities; they are identifiers, not credentials. The workspace exists only for this isolated process and is subject to its `/tmp` resource limit. An external storage Sink must implement its own authorized storage integration; the lifecycle adds no network access, host mounts or credentials.

`onDiscard` must remove only this attempt's staging, tolerate repeated requests and leave previous successful publications intact. Callbacks must be bounded and implement idempotency where external operations can have uncertain outcomes. The engine waits for successful cleanup before retrying. It never automatically retries once any Sink starts committing.

## Python SDK

Use `xstream_operator.sink.run_sink(process, on_complete, on_discard)`. The signatures are `process(ctx)`, `on_complete(ctx)` and `on_discard(ctx,error)`. Inputs use the normal `OperatorContext` API. `ctx.job_id` and `ctx.attempt_id` identify the attempt. Consume inputs through the context to cooperate with cancellation. Inference remains a separate `run_inference` entry point returning an empty outputs mapping. Rebuild/package runtimes with the matching SDK.

## Container and custom launch implementations

A custom runtime declaring role `sink` must implement the lifecycle protocol; returning process exit code 0 is not a commit. The host provides a private Unix socket in `XSTREAM_SINK_SOCKET`, plus `XSTREAM_JOB_ID` and `XSTREAM_ATTEMPT_ID`. Messages are UTF-8 JSON objects prefixed by a four-byte big-endian length (maximum 8192 bytes).

1. Connect before staging and send `{"state":"ready"}`.
2. Consume every input and finish staging; emit the ordinary operator frame `{"type":"sink_prepared","port":""}` with an empty payload. Stay alive.
3. Receive `{"state":"complete"}` or `{"state":"discard","message":"..."}` on the socket.
4. Run the callback and reply `{"state":"completed"}` or `{"state":"discarded"}`. Failures reply `commit_failed` or `discard_failed` and fail the process.
5. Release resources and exit. A successful commit requires acknowledgment and exit code 0.

A discard decision may arrive while processing. Stop processing cooperatively and clean staging before acknowledging. The SDKs handle these messages; business implementations should use their callbacks. A raw command or script that writes external effects immediately is not a safe staged Sink. Use a lifecycle-aware adapter or expose its computed data as a Transform output and let the application receive it through `client_sink`.

## Union and failure outcomes

Workers report `ready_to_commit` after processing, retaining Sink runtimes. Union waits for every worker and every final stream, then calls its certificate-bound internal `complete_execution` operation. Nested Unions defer commit to their parent. Cancellation uses the existing authorized cancellation operation and must confirm cleanup and stopped workers before retry.

Commit failure can leave several published results or an uncertain outcome. `commit_started` and Sink states expose this boundary; no distributed atomicity or crash recovery is promised. Tests should cover downstream failure after staging, cancellation before commit, cleanup failure, partial commit, idempotent cleanup, result receiver failure and runtime resource release.
