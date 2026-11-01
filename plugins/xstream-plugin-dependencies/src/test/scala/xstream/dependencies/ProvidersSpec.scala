package xstream.dependencies

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import xstream.operator._
import scala.jdk.CollectionConverters._

class ProvidersSpec extends AnyFunSuite {
  private val m=DependencyEnvironments.mapper
  test("dependency providers are discoverable without a core registry") {
    val providers=java.util.ServiceLoader.load(classOf[DependencyEnvironmentProvider]).iterator.asScala.toVector
    assert(providers.map(_.id).toSet==Set("python","jvm","native"))
  }
  test("JVM dependencies accept pinned coordinates, reject ranges and protected SDK overrides") {
    val root=Files.createTempDirectory("dependency-jvm-")
    try {
      val provider=new JvmDependencies
      def req(json:String)=DependencyRequest(root,"sha256:"+"a"*64,"sha256:"+"a"*64,m.readTree(json))
      assert(provider.coordinates(req("""{"provider":"jvm","coordinates":["a:b:1","a:b:1"]}"""))==Vector("a:b:1"))
      intercept[IllegalArgumentException](provider.coordinates(req("""{"provider":"jvm","coordinates":["a:b:[1,2)"]}""")))
      intercept[IllegalArgumentException](provider.coordinates(req("""{"provider":"jvm","coordinates":["org.apache.arrow:arrow-vector:18.3.0"]}""")))
      Files.writeString(root.resolve("pom.xml"),"""<project><dependencies><dependency><groupId>a</groupId><artifactId>b</artifactId><version>1</version></dependency></dependencies></project>""")
      assert(provider.coordinates(req("""{"provider":"jvm"}"""))==Vector("a:b:1"))
      Files.writeString(root.resolve("pom.xml"),"""<!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><project/>""")
      intercept[org.xml.sax.SAXParseException](provider.coordinates(req("""{"provider":"jvm"}""")))
    }finally DependencyEnvironments.delete(root)
  }
  test("native dependency bundles reject symlinks and preserve relative content") {
    val source=Files.createTempDirectory("dependency-native-");val work=Files.createTempDirectory("dependency-native-work-")
    try {
      Files.createDirectories(source.resolve("bin"));Files.writeString(source.resolve("bin/tool"),"#!/bin/sh\nprintf hello")
      val req=DependencyRequest(source,"sha256:"+"a"*64,"sha256:"+"a"*64,m.readTree("""{"provider":"native"}"""))
      Tools.native(req,work);assert(Files.readString(work.resolve("payload/bin/tool")).contains("hello"))
      Files.createSymbolicLink(source.resolve("bin/escape"),java.nio.file.Path.of("/etc/passwd"))
      intercept[IllegalArgumentException](Tools.native(req,work))
    }finally {DependencyEnvironments.delete(source);DependencyEnvironments.delete(work)}
  }
  test("cancelled dependency subprocesses stop promptly") {
    var checks=0
    intercept[java.util.concurrent.CancellationException](Tools.run(Seq("sh","-c","sleep 20"),()=>{checks+=1;throw new java.util.concurrent.CancellationException()}))
    assert(checks==1)
  }
}
