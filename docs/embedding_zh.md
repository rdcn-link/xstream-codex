# 嵌入 XStream 与收集 Job 结果

[English](embedding.md)

`LocalEngine` 在进程中承载目录和 `JobEngine`，配置与独立 Runner 一致，包括数据源、经审批的算子仓库和插件。运行时 classpath 需要包含 Job 所用的数据集、格式、执行器插件，以及文件执行历史插件。隔离执行算子需要 Docker 和匹配的运行时镜像。JVM Arrow 使用 `--add-opens=java.base/java.nio=ALL-UNNAMED` 和 `-Dio.netty.tryReflectionSetAccessible=true`。

Job 的 `client_sink` 标记返回给调用方的流；实际接收、暂存和发布由应用程序提供。例如 Job 末尾添加：

```yaml
- id: client_sink
  operator: client_sink@1.0
  inputs: {result: calculate:result}
```

## 将 bytes 结果保存到文件

`calculate` 步骤必须输出 CSV 的 **bytes**，不是 Arrow records。请预先创建 `/data`。`BytesFileSink` 默认接收 `result` 端口，其他端口名可通过构造器第二个参数指定。

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

`runToSink` 同步执行，成功后返回执行记录 ID。数据逐段写入目标文件同目录的临时文件，不收集到内存。失败尝试会删除暂存文件；只有完整 Job 成功才原子替换目标文件，要求该目录所在文件系统支持原子移动。关闭接收器会清理残留暂存。原子可见性不等于断电后的持久性保证。

`StreamCancel` 是取消和超时控制对象，不是结果收集器；创建它不会取消任务。其他线程可以调用 `close()` 请求取消，`Using` 会在调用结束后关闭它。取消会协作停止处理、关闭资源并丢弃未提交结果，不能撤销已提交的外部副作用。

## Records、Items 与自定义接收器

`ResultSink.accept` 接收具有端口名的 `start`、`data`、`end` 帧。对于 `records`，每个 data 帧包含一个单批次 Arrow IPC 流，可用 `ArrowStreamReader` 解码；在关闭前消费其借用向量，或复制需要保留的值，不能把 IPC 消息直接当 CSV 拼接。其他视图应使用其结构描述及对应 Arrow 编解码器。需要 CSV 文件时，在 `client_sink` 之前添加 records 转 CSV 的 Transform，或实现支持 records 的接收器。

自定义 `ResultSink` 提供 `accept(frame)`、`onDiscard(cause)`、`onComplete()`、`close()`。完成前的数据均为暂定结果，丢弃时清理暂存。接收器会在多次尝试间复用，因此丢弃必须重置全部端口状态；`close()` 由调用方负责。底层 `run(yaml, frameCallback, cancel, policy)` 可用于暂定数据流式处理，启用重试时回调必须处理 `attempt_failed`。参见[执行策略](execution-policy_zh.md)和[Sink 生命周期](sink-lifecycle_zh.md)。

`runToSink` 本身不物化结果，而是立即将每个帧交给接收器。`BytesFileSink` 选择暂存并原子发布文件；其他接收器可以直接流式处理，并自行实现丢弃逻辑。命令行 Runner 立即将 bytes/JSONL 写入 stdout，丢弃时在 stdout 单独一行输出 `**** XSTREAM: ATTEMPT DISCARDED ****`，在 stderr 报告失败原因；stdout 无法回滚此前字节；目标端需要在重试时重置时，应使用自定义 `ResultSink`。
