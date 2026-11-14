package xstream.operator

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class StreamEndpointSpec extends AnyFunSuite {
  test("transport programs allow only versioned bounded service requests") {
    val file=Files.createTempFile("endpoint-",".json")
    try {
      Files.writeString(file,"""{"api_version":1,"operation":"catalog.read"}""")
      assert(StreamEndpoint.read(file,"catalog.read")==StreamEndpoint.CatalogRead)
      intercept[IllegalArgumentException](StreamEndpoint.read(file,"client.receive"))
      Seq("""{"api_version":1,"operation":"host.exec"}""", """{"api_version":1,"operation":"catalog.read","command":"sh"}""",
        """{"api_version":2,"operation":"catalog.read"}""", """{"api_version":1,"operation":"catalog.read"} {}""").foreach { json=>
        Files.writeString(file,json);intercept[IllegalArgumentException](StreamEndpoint.read(file,"catalog.read"))
      }
      Files.writeString(file," "*4097)
      intercept[IllegalArgumentException](StreamEndpoint.read(file,"catalog.read"))
    }finally Files.delete(file)
  }
}
