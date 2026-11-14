package xstream.engine

import _root_.xstream.data._


import org.scalatest.funsuite.AnyFunSuite

class RecordedJobGraphSpec extends AnyFunSuite {
  test("stored scripts render independently of current operator parameter contracts") {
    val script="""name: Stored read
                  |steps:
                  |  - id: read
                  |    operator: item_source@1.0
                  |    inputs: {}
                  |    params: {path: /old/item}
                  |  - id: filter
                  |    operator: filter@1.0
                  |    inputs: {source: 'read:result'}
                  |  - id: client_sink
                  |    operator: client_sink@1.0
                  |    inputs: {result: filter}
                  |""".stripMargin
    val graph=RecordedJobGraph(script)
    assert(graph.path("steps").get(1).path("inputs").get(0).path("task").asText=="read")
    assert(graph.path("steps").get(2).path("inputs").get(0).path("task").asText=="filter")
    intercept[IllegalArgumentException](Jobs.parse(script))
  }
}
