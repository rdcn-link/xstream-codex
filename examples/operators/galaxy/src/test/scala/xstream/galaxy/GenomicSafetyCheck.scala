package xstream.galaxy
import java.io._
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.ObjectMapper
import xstream.execution.StreamDescriptor
object GenomicSafetyCheck {
 def main(args:Array[String]):Unit={
  val mapper=new ObjectMapper();val p=mapper.readTree("{}");val inputs=Map("source"->StreamDescriptor(Set("bytes"),Set("text"),certainty="known"))
  def rejects(f: => Unit):Unit={var failed=false;try f catch{case _:IllegalArgumentException=>failed=true};assert(failed)}
  Seq("{\"start\":0}","{\"start\":3,\"end\":2}","{\"column\":-1}","{\"fastq\":\"true\"}","{\"ignore_prefixes\":\"a\\nb\"}").foreach(s=>rejects(Trim.inferOutputs(inputs,mapper.readTree(s))))
  Seq("{\"window_size\":0}","{\"offset\":-1}","{\"start_column\":2,\"end_column\":2}").foreach(s=>rejects(MakeWindows.inferOutputs(inputs,mapper.readTree(s))))
  Seq("{\"reference_sequence\":\"20\",\"region\":\"20:1..3\"}","{\"region\":\"20:0..3\"}","{\"region\":\"20:3..1\"}","{\"region\":\";sh\"}","{\"keep_info\":\"TV\",\"discard_info\":\"DP\"}","{\"quality\":-1}","{\"quality_operator\":\"eval\"}").foreach(s=>rejects(VcfExtract.inferOutputs(inputs,mapper.readTree(s))))
  def run(op:GenomicTextOperator,s:String,params:String="{}"):String={val out=new ByteArrayOutputStream;op.transform(new ByteArrayInputStream(s.getBytes(UTF_8)),out,mapper.readTree(params));out.toString("UTF-8")}
  rejects(run(MakeWindows,"chr1\t0\t100001","{\"window_size\":1}"))
  rejects(run(MakeWindows,"chr1\t0\t999\t"+"x"*100000,"{\"window_size\":1}"))
  rejects(run(MakeWindows,"chr1\t10\t2"))
  rejects(run(Trim,"one","{\"column\":2}"))
  assert(run(MakeWindows,"chr1\t9223372036854775797\t9223372036854775807","{\"window_size\":5}")=="chr1\t9223372036854775797\t9223372036854775802\nchr1\t9223372036854775802\t9223372036854775807\n")
  assert(run(MakeWindows,"chr1\t0\t3","{\"window_size\":4,\"offset\":1}").isEmpty)
  assert(run(VcfExtract,"20\t1\t.\tA\tG\t.\tPASS\tSDP=2","{\"keep_info\":\"DP\"}").isEmpty)
  assert(run(VcfExtract,"20\t1\t.\tA\tG\t.\tPASS\tDP=2","{\"quality_operator\":\"ge\"}").isEmpty)
  rejects(run(VcfExtract,"20\t1\t.\tA\tG\tNaN\tPASS\t.","{\"quality_operator\":\"ge\"}"))
  rejects(run(VcfExtract,"20\t1"))
  Seq(Trim,MakeWindows,VcfExtract).foreach{op=>
   rejects(op.inferOutputs(inputs,mapper.readTree("{\"unknown\":1}")))
   Thread.currentThread.interrupt()
   try{op.transform(new ByteArrayInputStream(Array.emptyByteArray),new ByteArrayOutputStream,p);throw new AssertionError("cancellation ignored")}
   catch{case _:CancellationException=>()}finally Thread.interrupted()
   var io=false;try op.transform(new InputStream{def read():Int=throw new IOException("test")},new ByteArrayOutputStream,p)catch{case _:IOException=>io=true};assert(io)
   var utf8=false;try op.transform(new ByteArrayInputStream(Array(0xff.toByte)),new ByteArrayOutputStream,p)catch{case _:java.nio.charset.MalformedInputException=>utf8=true};assert(utf8)
   rejects(op.transform(new ByteArrayInputStream(("x"*1048577).getBytes(UTF_8)),new ByteArrayOutputStream,p))
  }
  // Cancellation is checked inside expansion, not just when reading the next input line.
  val cancelOutput=new OutputStream{var bytes=0;def write(b:Int):Unit={bytes+=1;if(bytes==100)Thread.currentThread.interrupt()}}
  try{MakeWindows.transform(new ByteArrayInputStream("chr1\t0\t9999".getBytes(UTF_8)),cancelOutput,mapper.readTree("{\"window_size\":1}"));throw new AssertionError("window cancellation ignored")}
  catch{case _:CancellationException=>()}finally Thread.interrupted()
  println("PASS trim/VCF/window validation, expansion limits, coordinate boundaries, exact tags, missing quality, UTF-8, I/O and mid-expansion cancellation")
 }
}
