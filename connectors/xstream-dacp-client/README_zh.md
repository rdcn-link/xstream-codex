# XStream 远程数据连接器

[English](README.md)

Runner 和设计器使用这个自包含连接器访问远程 DACP 目录、Item 视图及 Job 结果流。公开包名为 `xstream.connector.dacp.client`。依赖 XStream 库及仓库内的[通信定义](../proto/dacp.proto)，不需要 DACP 源码目录或服务器制品。

```scala
import xstream.connector.dacp.client.{Source, TlsConfig}
import java.nio.file.Path
import scala.util.Using

val tls = TlsConfig(ca = Some(Path.of("ca.crt")),
  tokenFile = Some(Path.of("reader.token")))
val directory = Source.datasets("dacp://data.example.org:8815", tls)
Using.resource(Source.open("dacp://data.example.org:8815/weather", tls)) { dataset =>
  println(dataset.list("/"))
}
```

匿名公开元数据不需要凭据即可列出。受保护读取使用配置的 Token；CA 信任通过 `TlsConfig.ca` 或系统信任根提供。如使用客户端证书，证书与密钥必须成对提供。Dataset、Item 读取器和 Job 流需要关闭，提前退出时同样如此。Arrow root 是借用并复用的，前进前必须消费或复制批次。

`Job.run` 返回可关闭的 `OutputEvent` 迭代器，支持变量、策略、重试通知和远程重放请求。传输失败转为带稳定错误码的 `DacpException`。失败的流必须重新打开；提前关闭会取消当前交换。本地 Job 直接使用嵌入引擎。

```bash
(cd connectors/xstream-dacp-client && sbt test)
```

测试使用本地协议桩，不需要运行 DACP 服务。参见[引擎配置](../../docs/xstream_zh.md)。
