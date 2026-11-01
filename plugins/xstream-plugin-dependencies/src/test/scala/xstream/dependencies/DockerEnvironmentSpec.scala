package xstream.dependencies
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import xstream.operator._
class DockerEnvironmentSpec extends AnyFunSuite {
  test("real Python and native environments preserve SDK paths and reuse locked images") {
    if(sys.env.get("XSTREAM_DEPENDENCY_DOCKER_TESTS")!=Some("1"))cancel("set XSTREAM_DEPENDENCY_DOCKER_TESTS=1 with Docker access and a uv-enabled builder")
    val root=Files.createTempDirectory("xstream-dependency-live-")
    try {
      val source=Files.createDirectories(root.resolve("source"));val cache=new DependencyEnvironmentCache(root.resolve("cache"))
      Files.writeString(source.resolve("pyproject.toml"),"""[project]
name = "test-identity"
version = "1.0.0"
requires-python = ">=3.11,<3.14"
dependencies = []
""")
      val base=Tools.run(Seq("docker","image","inspect","--format","{{.Id}}","xstream/operator-python:1.0"),()=>())
      val builder=Tools.run(Seq("docker","image","inspect","--format","{{.Id}}","xstream/dependency-python-builder:test"),()=>())
      val req=DependencyRequest(source,base,builder,DependencyEnvironments.mapper.readTree("""{"provider":"python"}"""))
      val first=cache.acquire(new PythonDependencies,req,()=>())
      val image=first.value.image;val digest=first.value.fingerprint
      assert(Tools.run(Seq("docker","run","--rm","--network","none","--entrypoint","/usr/local/bin/python",image,"-c","import xstream_operator,pyarrow;print('sdk-ready')"),()=>()).contains("sdk-ready"))
      first.close()
      val second=cache.acquire(new PythonDependencies,req,()=>());assert(second.value.image==image && second.value.fingerprint==digest);second.close()
      Files.createDirectories(source.resolve("bin"));Files.writeString(source.resolve("bin/hello"),"#!/usr/local/bin/python\nprint('native-ready')")
      val native=cache.acquire(new NativeDependencies,req.copy(settings=DependencyEnvironments.mapper.readTree("""{"provider":"native"}""")),()=>())
      try assert(Tools.run(Seq("docker","run","--rm","--network","none","--entrypoint","/opt/xstream/bin/hello",native.value.image),()=>()).contains("native-ready"))finally native.close()
    }finally DependencyEnvironments.delete(root)
  }
}
