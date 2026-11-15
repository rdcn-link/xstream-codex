# Embedding XStream and collecting Job results

[中文](embedding_zh.md)

`LocalEngine` hosts an in-process catalog and `JobEngine`. Configure it with the same settings as the standalone Runner, including data sources, approved operator repositories and plugins. Include the dataset/format/executor plugins required by the Job and the file execution-history plugin on the runtime classpath. Isolated operator execution requires Docker and matching operator runtimes. For JVM Arrow use `--add-opens=java.base/java.nio=ALL-UNNAMED` and `-Dio.netty.tryReflectionSetAccessible=true`.

A Job's `client_sink` identifies the streams returned to the caller. The application supplies the implementation that receives, stages and publishes these results. For example, end a Job with:

```yaml
- id: client_sink
  operator: client_sink@1.0
  inputs: {result: calculate:result}
```

## Write a bytes result to a file

The `calculate` step must emit CSV **bytes**, not Arrow records. Ensure `/data` exists. `BytesFileSink` defaults to result port `result`; supply a different port name as its second constructor argument if necessary.

```scala
import java.nio.file.{Files, Path}
import scala.util.Using
import xstream.data.PluginEnvironment
import xstream.engine.LocalEngine
import xstream.execution.{BytesFileSink, StreamCancel}
import xstream.util.{Json, YamlDocument}

val settings = YamlDocument.parse(Files.readString(Path.of("runner.yaml")))
val job = Files.readString(Path.of("job.yaml"))
Using.resource(new PluginEnvironment()) { plugins =>
  Using.resource(new LocalEngine(settings, plugins)) { host =>
    Using.resource(new StreamCancel(timeoutSeconds = 1800)) { cancel =>
      Using.resource(new BytesFileSink(Path.of("/data/result.csv"))) { sink =>
        val id = host.engine.runToSink(job, sink, cancel,
          policy = Json.obj().put("max_retries", 1))
        println(s"Completed: $id")
      }
    }
  }
}
```

`runToSink` is synchronous and returns the execution record ID after success. Data is written incrementally to a temporary sibling file, not collected in memory. Failed attempts delete their staging. Only whole-Job completion atomically replaces the destination; the filesystem must support atomic moves in that directory. Closing the receiver cleans remaining staging. Atomic visibility is not a guarantee of durability through a power failure.

`StreamCancel` is the cancellation/timeout scope, not the result collector. Creating it does not cancel anything. Another thread can call `close()` to request cancellation; `Using` closes it after the call. Cancellation cooperatively stops work, closes resources and discards uncommitted results. It cannot undo a committed external effect.

## Records, Items and custom receivers

`ResultSink.accept` receives `start`, `data` and `end` frames with a named port. For `records`, each data frame contains an Arrow IPC stream holding one batch. Decode it with `ArrowStreamReader`, consume its borrowed vectors before closing, and write or copy selected values; do not concatenate IPC messages as CSV. For other views use their advertised structure and the corresponding Arrow codec. To obtain a CSV file, add a records-to-CSV Transform before `client_sink`, or implement a records-aware receiver.

Custom `ResultSink` implementations provide `accept(frame)`, `onDiscard(cause)`, `onComplete()` and `close()`. Keep data provisional until completion and release staging on discard. The receiver is reused across attempts, so discard must reset all per-port state. The caller owns `close()`. The low-level `run(yaml, frameCallback, cancel, policy)` remains useful for provisional streaming; its callback must handle `attempt_failed` when retries are enabled. See [execution policies](execution-policy.md) and [Sink lifecycle](sink-lifecycle.md).

`runToSink` does not itself materialize results: it forwards each frame immediately to the receiver. `BytesFileSink` chooses staged atomic file publication; other receivers can stream data and implement their own discard behavior. The CLI Runner streams bytes/JSONL to stdout immediately. On discard it writes `**** XSTREAM: ATTEMPT DISCARDED ****` on a separate line to stdout and reports the reason on stderr; stdout itself cannot roll back earlier bytes; use a custom `ResultSink` when the destination must reset on retry.
