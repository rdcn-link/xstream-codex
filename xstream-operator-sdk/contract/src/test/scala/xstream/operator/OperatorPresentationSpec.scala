package xstream.operator



import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.scalatest.funsuite.AnyFunSuite

class OperatorPresentationSpec extends AnyFunSuite {
  val mapper=new ObjectMapper()
  def valid=mapper.readTree("""{"icon":{"path":"M3 5h18v14H3zM3 10h18"}}""").asInstanceOf[ObjectNode]
  test("operator roles determine colors and authors cannot provide a color"){
    assert(OperatorPresentation.roleColor("source")=="#16835d")
    assert(OperatorPresentation.roleColor("transform")=="#376ac7")
    assert(OperatorPresentation.roleColor("sink")=="#bf7416")
    for(color<-Seq("#16835d","#ffffff")){val n=valid;n.path("icon").asInstanceOf[ObjectNode].put("color",color);intercept[IllegalArgumentException](OperatorPresentation.validate(n))}
  }
  test("portable icons work before review; published metadata supports multiple categories"){
    OperatorPresentation.validate(valid)
    val n=valid;n.putArray("categories").add("Climate").add("Tables");OperatorPresentation.validate(n)
  }
  test("missing metadata and active or externally loaded icons are rejected"){
    for(field<-Seq("icon")){val n=valid;n.remove(field);intercept[IllegalArgumentException](OperatorPresentation.validate(n))}
    for(category<-Seq("", " padded", "bad\nlabel", "x"*81)){val n=valid;n.putArray("categories").add(category);intercept[IllegalArgumentException](OperatorPresentation.validate(n))}
    val legacy=valid;legacy.put("category","Legacy");intercept[IllegalArgumentException](OperatorPresentation.validate(legacy))
    val duplicate=valid;duplicate.putArray("categories").add("Climate").add("climate");intercept[IllegalArgumentException](OperatorPresentation.validate(duplicate))
    for(path<-Seq("https://example.com/a.svg","<svg onload='alert(1)'/>","M","MNaN 1", "M"+"1"*2048)){
      val n=valid;n.path("icon").asInstanceOf[ObjectNode].put("path",path);intercept[IllegalArgumentException](OperatorPresentation.validate(n))
    }
    val n=valid;n.path("icon").asInstanceOf[ObjectNode].put("color","red;display:none");intercept[IllegalArgumentException](OperatorPresentation.validate(n))
  }
}
