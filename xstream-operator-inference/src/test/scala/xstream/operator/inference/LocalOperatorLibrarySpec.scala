package xstream.operator.inference

import java.nio.file.{Files,Path}
import com.fasterxml.jackson.databind.JsonNode
import org.scalatest.funsuite.AnyFunSuite
import xstream.data.{Data,PluginEnvironment,PluginRegistration}
import xstream.operator.LocalOperatorBuilder

class LocalOperatorLibrarySpec extends AnyFunSuite {
  test("explicit paths and library references reuse builds; changed sources rebuild and failures do not cache") {
    val root=Files.createTempDirectory("local-library-");val directory=Files.createDirectories(root.resolve("library/filter/1.0"))
    val builder=new LocalOperatorBuilder {
      val id="test";var builds=0;var fail=false
      def build(dir:Path,settings:JsonNode,destination:Path,check:()=>Unit):Path={
        builds+=1;check();require(!fail,"build failed")
        val output=destination.resolve("operator.jar");Files.writeString(output,Files.readString(dir.resolve("src/code.scala")));output
      }
    }
    val registry=new PluginEnvironment(registrations=Seq(PluginRegistration(builder)))
    val pool=new InferenceWorkerPool(InferenceWorkerSettings())
    val env=new OperatorEnvironment {
      def localOperatorLibrary=root.resolve("library");def operatorCache=root.resolve("cache");def operatorMaxBytes=1024L
      def remoteOperatorLibraryUrls=Seq.empty;def plugins=registry;def inferenceWorkerPool=pool
    }
    try {
      Files.createDirectories(directory.resolve("src"));Files.writeString(directory.resolve("src/code.scala"),"one")
      Files.writeString(directory.resolve("operator.yaml"),"""name: filter
version: '1.0'
language: scala
runtime: jvm@1.1
entrypoint: Main
inputs: {}
parameters: {type: object, properties: {}, required: [], additionalProperties: false}
icon: {path: 'M0 0L1 1', color: '#14866b'}
""")
      Files.writeString(directory.resolve("local.yaml"),"image: test/runtime:1\nexecutor: jvm\nbuild: {provider: test}\n")
      val first=LocalOperatorLibrary.prepare(env,root,"filter@1.0",()=>())
      val again=LocalOperatorLibrary.prepare(env,root,"./library/filter/1.0",()=>())
      assert(first.artifact==again.artifact && builder.builds==1)
      Files.writeString(first.artifact,"corrupted")
      assert(Files.readString(LocalOperatorLibrary.prepare(env,root,"filter@1.0",()=>()).artifact)=="one")
      assert(builder.builds==2)
      Files.writeString(directory.resolve("src/code.scala"),"two")
      val changed=LocalOperatorLibrary.prepare(env,root,"filter@1.0",()=>())
      assert(changed.artifact!=first.artifact && Files.readString(changed.artifact)=="two")
      assert(Files.readString(first.artifact)=="one")
      Files.writeString(directory.resolve("src/code.scala"),"three");builder.fail=true
      intercept[IllegalArgumentException](LocalOperatorLibrary.prepare(env,root,"filter@1.0",()=>()))
      builder.fail=false
      assert(Files.readString(LocalOperatorLibrary.prepare(env,root,"filter@1.0",()=>()).artifact)=="three")
      intercept[java.util.concurrent.CancellationException](LocalOperatorLibrary.prepare(env,root,"filter@1.0",()=>throw new java.util.concurrent.CancellationException()))
      intercept[java.nio.file.NoSuchFileException](LocalOperatorLibrary.prepare(env,root,"filter@2.0",()=>()))
    }finally {pool.close();registry.close();Data.deleteTree(root)}
  }
}
