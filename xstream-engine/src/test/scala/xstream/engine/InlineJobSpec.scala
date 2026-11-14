package xstream.engine

import org.scalatest.funsuite.AnyFunSuite
import xstream.execution.{InlineOperators,RemoteJobPolicy}
import xstream.util.Json

class InlineJobSpec extends AnyFunSuite {
  private val yaml="""name: Local development
    |operators:
    |  clean:
    |    type: script
    |    role: source
    |    runtime: python
    |    interpreter: /usr/local/bin/python
    |    script: {source: 'print("ready")'}
    |    inputs: {}
    |    outputs: {result: {view: bytes, binding: stdout, format: text}}
    |steps:
    |- id: run
    |  operator: {inline: clean}
    |  inputs: {}
    |- id: client_sink
    |  operator: client_sink@1.0
    |  inputs: {result: run}
    |""".stripMargin
  test("inline definitions survive parsing and references resolve by name") {
    val plan=Jobs.parse(yaml)
    assert(plan.hasInlineDefinitions && plan.inlineOperators.keySet==Set("clean"))
    assert(plan.steps.head.inlineOperator.get==plan.inlineOperators("clean"))
    intercept[IllegalArgumentException](Jobs.parse(yaml.replace("inline: clean","inline: missing")))
    intercept[IllegalArgumentException](Jobs.parse(yaml.replace("inline: clean","inline: clean, local: ./tool")))
    intercept[IllegalArgumentException](Jobs.parse(yaml.replace("runtime: python","runtime: 123")))
  }
  test("remote policy rejects even empty or unused definitions and all local references") {
    val ordinary="name: Remote\nsteps: [{id: run, operator: identity@1.0, inputs: {}}]\noutputs: {result: run}\n"
    RemoteJobPolicy.validateYaml(ordinary)
    Seq(yaml,ordinary+"operators: {}\n",ordinary+"operators: null\n",yaml.replace("{inline: clean}","identity@1.0"),ordinary.replace("identity@1.0","{local: ./tool}"),ordinary.replace("identity@1.0","{runtime: python, source: code}")).foreach { text=>
      assert(intercept[IllegalArgumentException](RemoteJobPolicy.validateYaml(text)).getMessage.contains("publish the operators first"))
    }
  }
  test("SDK inline declarations support JVM class and Python module entrypoints") {
    for((path,entry)<-Seq("./app.jar"->"example.Filter","./app.whl"->"example.filter","./filter.py"->"filter")){
      val d=Json.obj().put("name","SDK")
      d.putObject("operators").putObject("filter").put("role","source").put("type","sdk").put("runtime","tools").putObject("program").put("path",path).put("entrypoint",entry)
      d.path("operators").path("filter").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].putObject("inputs")
      assert(InlineOperators.definitions(d)("filter").definition.path("program").path("entrypoint").asText==entry)
    }
  }
}
