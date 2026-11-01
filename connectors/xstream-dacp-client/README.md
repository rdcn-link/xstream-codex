# XStream remote data connector

[中文](README_zh.md)

The runner and designer use this self-contained connector for DACP remote catalogs, Item views and Job result streams. Its public package is `xstream.connector.dacp.client`. It depends on XStream libraries and the bundled [wire schema](../proto/dacp.proto), without a DACP checkout or server artifact.

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

Anonymous public metadata can be listed without credentials. Protected reads use the configured token; CA trust uses `TlsConfig.ca` or system roots. Client certificates, when supplied, require both certificate and key. Close datasets, Item readers and Job streams, including on early exit. Arrow roots are borrowed and reused: consume or copy batches before advancing.

`Job.run` returns a closeable `OutputEvent` iterator. It supports variables, policies, retry notifications and replay requests to remote services. Transport failures become `DacpException` with stable error codes. Failed streams must be reopened; closing early cancels the active exchange. Local Jobs use the embedded engine directly.

```bash
(cd connectors/xstream-dacp-client && sbt test)
```

Tests use local protocol fixtures; a running DACP service is not required. See [engine configuration](../../docs/xstream.md).
