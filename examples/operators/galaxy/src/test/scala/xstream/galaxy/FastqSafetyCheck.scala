package xstream.galaxy
import java.io._
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.ObjectMapper
object FastqSafetyCheck {
 def main(args:Array[String]):Unit={
  val mapper=new ObjectMapper;val p=mapper.readTree("{}")
  Seq(FastqToFasta,FastqToQual).foreach{op=>
   Thread.currentThread.interrupt()
   try{op.transform(new ByteArrayInputStream(Array.emptyByteArray),new ByteArrayOutputStream,p);throw new AssertionError("cancellation ignored")}
   catch{case _:CancellationException=>()}finally Thread.interrupted()
   var failed=false
   try op.transform(new InputStream{def read():Int=throw new IOException("input failure")},new ByteArrayOutputStream,p)catch{case _:IOException=>failed=true};assert(failed)
   failed=false
   try op.transform(new ByteArrayInputStream("@r\nAC\n+\nII\n".getBytes(UTF_8)),new OutputStream{def write(b:Int):Unit=throw new IOException("output failure")},p)catch{case _:IOException=>failed=true};assert(failed)
   val out=new ByteArrayOutputStream
   op.transform(new ByteArrayInputStream("@one\nA\n+\nI\n@two\nC\n+two\nJ".getBytes(UTF_8)),out,p)
   assert(out.toString("UTF-8")== (if(op==FastqToFasta)">one\nA\n>two\nC\n"else">one\n9\n>two\n10\n"))
  }
  println("PASS FASTQ cancellation, input/output failure propagation, multiple records and final line without newline")
 }
}
