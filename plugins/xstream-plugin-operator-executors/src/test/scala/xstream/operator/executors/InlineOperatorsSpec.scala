package xstream.operator.executors

import org.scalatest.funsuite.AnyFunSuite
import xstream.execution._
import xstream.util.YamlDocument
import xstream.data.{PluginEnvironment,Data}
import java.nio.file.Files

class InlineOperatorsSpec extends AnyFunSuite {
  private val declaration="""type: script
    |role: transform
    |runtime: tools
    |interpreter: /usr/local/bin/python
    |script: {source: 'import sys; print(sys.argv[1])'}
    |args: [{param: message}, {input: source}, {output: result}]
    |inputs: {source: {view: bytes, binding: file}}
    |outputs: {result: {view: bytes, binding: file, format: text}}
    |""".stripMargin
  test("built-in inline adapters use ServiceLoader and stage ordinary scripts") {
    val plugins=new PluginEnvironment();val root=Files.createTempDirectory("inline-provider-")
    try {
      assert(Set("sdk","command","script").subsetOf(plugins.services(classOf[InlineOperatorProvider]).map(_.id).toSet))
      val provider=plugins.byId(classOf[InlineOperatorProvider],"script")
      val prepared=provider.prepare(InlineOperator("script",YamlDocument.parse(declaration)),root,root,1024*1024,()=>())
      assert(Files.readString(prepared.program.artifact.get).contains("sys.argv"))
      val cmd=new CommandExecutor().command(prepared.program,prepared.program.artifact.map(p=>"/operator/"+p.getFileName))
      assert(cmd.entrypoint.get.contains("-c") && !cmd.executableScratch && prepared.inputs("source").representations==Set("bytes"))
      assert(new CommandExecutor().inferenceWorkerCommand(prepared.program,Some("/operator/script.source")).arguments.head=="infer-worker")
    }finally {plugins.close();Data.deleteTree(root)}
  }
  test("tool declarations reject invalid mappings and shell-like implicit argument expansion") {
    val provider=new InlineScriptProvider()
    Seq(declaration.replace("view: bytes","view: records"),declaration.replace("{param: message}","{shell: message}"),declaration.replace("{input: source}","{input: missing}"),declaration.replace("{output: result}","literal"),declaration.replace("binding: file","binding: stdout"),declaration+"unexpected: true\n").foreach(d=>intercept[IllegalArgumentException](provider.validate(YamlDocument.parse(d))))
  }
  test("SDK artifact paths are relative to Job base and copied into private staging") {
    val root=Files.createTempDirectory("inline-sdk-");val base=Files.createDirectories(root.resolve("job"));val stage=Files.createDirectories(root.resolve("stage"))
    try {
      Files.writeString(base.resolve("operator.py"),"print('SDK')")
      val d=YamlDocument.parse("type: sdk\nrole: source\nruntime: python\nprogram: {path: ./operator.py, entrypoint: operator}\ninputs: {}\noutputs: {result: {view: bytes}}\n")
      val prepared=new InlineSdkProvider().prepare(InlineOperator("sdk",d),base,stage,1024,()=>())
      assert(prepared.program.artifact.contains(stage.resolve("operator.py")))
      Files.delete(base.resolve("operator.py"));assert(Files.readString(prepared.program.artifact.get)=="print('SDK')")
      intercept[IllegalArgumentException](new InlineSdkProvider().prepare(InlineOperator("sdk",d),base,stage,1024,()=>()))
    }finally Data.deleteTree(root)
  }
}
