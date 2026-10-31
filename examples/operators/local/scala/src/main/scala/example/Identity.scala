package example

import java.io.{InputStream,OutputStream}
import com.fasterxml.jackson.databind.JsonNode
import xstream.operator.ByteStreamOperator

object Identity extends ByteStreamOperator {
  override def transform(input:InputStream,output:OutputStream,params:JsonNode):Unit={
    input.transferTo(output)
  }
}
