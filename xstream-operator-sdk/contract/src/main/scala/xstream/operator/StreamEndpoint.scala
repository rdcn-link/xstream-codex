package xstream.operator

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.{Files,Path}
import scala.jdk.CollectionConverters._

/** Declarative programs for the host's bounded transport services. No package code is loaded into the host JVM. */
object StreamEndpoint {
  val CatalogRead="catalog.read"
  val ClientReceive="client.receive"
  private val mapper=new ObjectMapper()
  def read(path:Path,entrypoint:String):String={
    require(Files.isRegularFile(path) && Files.size(path)<=4096,"invalid stream endpoint program")
    val parser=mapper.getFactory.createParser(path.toFile)
    parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    val n=try {val value=mapper.readTree[com.fasterxml.jackson.databind.JsonNode](parser);require(parser.nextToken()==null,"trailing stream endpoint content");value}finally parser.close()
    require(n!=null && n.isObject && n.fieldNames.asScala.toSet==Set("api_version","operation"),"invalid stream endpoint fields")
    require(n.path("api_version").isIntegralNumber && n.path("api_version").asInt==1,"unsupported stream endpoint program")
    val operation=n.path("operation").asText
    require(Set(CatalogRead,ClientReceive)(operation) && operation==entrypoint,"stream endpoint operation mismatch")
    operation
  }
}
