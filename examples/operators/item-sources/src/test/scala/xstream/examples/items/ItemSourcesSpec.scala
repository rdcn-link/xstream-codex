package xstream.examples.items

import org.scalatest.funsuite.AnyFunSuite
import xstream.data._
import xstream.execution._

class ItemSourcesSpec extends AnyFunSuite {
  test("Sources infer collection references without opening member data") {
    val params=Data.mapper.readTree("""{"uri":"context:/science/input.csv"}""")
    for(op<-Seq(FolderItemsSource,CsvItemsSource)) {
      val d=op.inferOutputs(Map.empty,params).outputs("result")
      assert(d.representations==Set("items") && d.structure.get.path("cardinality").asText=="many")
    }
  }
  test("Sources reject upstream inputs and invalid selection parameters") {
    val params=Data.mapper.readTree("""{"uri":"context:/science/input.csv"}""")
    val input=StreamDescriptor(Set("items"),Set("items"))
    intercept[IllegalArgumentException](FolderItemsSource.inferOutputs(Map("source"->input),params))
    intercept[IllegalArgumentException](FolderItemsSource.inferOutputs(Map.empty,Data.mapper.readTree("""{"uri":"context:/science","recursive":"yes"}""")))
    intercept[IllegalArgumentException](CsvItemsSource.inferOutputs(Map.empty,Data.mapper.readTree("""{"uri":"context:/science/input.csv","uri_column":""}""")))
  }
}
