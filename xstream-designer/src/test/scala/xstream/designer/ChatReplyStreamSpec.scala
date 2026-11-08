package xstream.designer
import org.scalatest.funsuite.AnyFunSuite
class ChatReplyStreamSpec extends AnyFunSuite {
 test("reply is emitted before the enclosing proposal is complete"){
  val received=scala.collection.mutable.ArrayBuffer.empty[String];val stream=new ChatReplyStream(received+=_)
  stream.feed("{\"reply\":\"First ");assert(received.mkString=="First ")
  stream.feed("answer\",\"job_yaml\":\"name: Secret draft\",");assert(received.mkString=="First answer")
  stream.feed("\"variables_yaml\":\"{}\"}");assert(!received.mkString.contains("Secret"))
 }
 test("fragmented escapes, Unicode and surrogate pairs decode without exposing other fields"){
  val received=new StringBuilder;val stream=new ChatReplyStream(received.append(_))
  val document="{\"metadata\":{\"reply\":\"hidden\"},\"reasoning\":\"private\",\"reply\":\"Hello\\n\\u4e2d\\u6587 \\ud83c\\udf0d \\\"quoted\\\" \\\\ path\"}"
  document.grouped(1).foreach(stream.feed)
  assert(received.toString=="Hello\n中文 🌍 \"quoted\" \\ path")
 }
}
