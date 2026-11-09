package xstream.galaxy
import java.io._
import java.nio.file.{Files,Path}
import java.nio.charset.StandardCharsets.UTF_8
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CancellationException
import scala.jdk.CollectionConverters._
object ExtendedSafetyCheck {
 def main(args:Array[String]):Unit={
  val mapper=new ObjectMapper;val specs=mapper.readTree(Files.readString(Path.of("extra-specs.json")))
  var total=0
  specs.fields.asScala.foreach{entry=>
   val s=entry.getValue;val op=Class.forName(s.path("entrypoint").asText+"$").getField("MODULE$").get(null).asInstanceOf[ExtendedOperator]
   val f=s.path("fixture");val data=f.path("data").asText.getBytes(UTF_8);val params=f.path("params");val out=new ByteArrayOutputStream
   op.transform(new ByteArrayInputStream(data),out,params);assert(out.toString("UTF-8")==f.path("expected").asText,entry.getKey+" actual="+out.toString("UTF-8"))
   def fails(body: => Unit):Unit={var failed=false;try body catch{case _:IllegalArgumentException=>failed=true};assert(failed,entry.getKey)}
   fails(op.transform(new ByteArrayInputStream(data),new ByteArrayOutputStream,mapper.readTree("{\"unsupported\":1}")))
   Thread.currentThread.interrupt()
   try{op.transform(new ByteArrayInputStream(data),new ByteArrayOutputStream,params);throw new AssertionError("cancellation ignored")}catch{case _:CancellationException=>()}finally Thread.interrupted()
   var io=false;try op.transform(new InputStream{def read():Int=throw new IOException("input")},new ByteArrayOutputStream,params)catch{case _:IOException=>io=true};assert(io)
   io=false;try op.transform(new ByteArrayInputStream(data),new OutputStream{def write(b:Int):Unit=throw new IOException("output")},params)catch{case _:IOException=>io=true};assert(io)
   var utf8=false;try op.transform(new ByteArrayInputStream(Array(0xff.toByte)),new ByteArrayOutputStream,params)catch{case _:java.nio.charset.MalformedInputException=>utf8=true};assert(utf8)
   fails(op.transform(new ByteArrayInputStream(("A"*1048577).getBytes(UTF_8)),new ByteArrayOutputStream,params))
   total+=7;println("PASS fixture, validation, cancellation, I/O, encoding and line budget: "+entry.getKey)
  }
  val regressions=mapper.readTree(Files.readString(Path.of("extended-regressions.json")))
  Seq("positive","negative").foreach{kind=>regressions.path(kind).elements.asScala.foreach{c=>
   val spec=specs.path(c.path("operator").asText);val op=Class.forName(spec.path("entrypoint").asText+"$").getField("MODULE$").get(null).asInstanceOf[ExtendedOperator]
   val out=new ByteArrayOutputStream;var failed=false
   try op.transform(new ByteArrayInputStream(c.path("data").asText.getBytes(UTF_8)),out,c.path("params"))catch{case _:IllegalArgumentException=>failed=true}
   if(kind=="positive")assert(!failed&&out.toString("UTF-8")==c.path("expected").asText,c.path("operator").asText+" actual="+out.toString("UTF-8")+" expected="+c.path("expected").asText)
   else assert(failed,"malformed input accepted: "+c)
   total+=1
  }}
  println(s"PASS $total extended adapter checks, including upstream golden fixtures")
 }
}
