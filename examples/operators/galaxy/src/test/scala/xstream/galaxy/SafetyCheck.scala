package xstream.galaxy
import java.io._
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.ObjectMapper
import xstream.execution.StreamDescriptor
object SafetyCheck {
  def main(args:Array[String]):Unit={
    val mapper=new ObjectMapper();val p=mapper.readTree("{}")
    def rejects(f: => Unit):Unit={var failed=false;try f catch{case _:IllegalArgumentException=>failed=true};assert(failed)}
    Thread.currentThread.interrupt()
    try {AddColumn.transform(new ByteArrayInputStream("abc".getBytes(UTF_8)),new ByteArrayOutputStream,p);throw new AssertionError("cancellation ignored")}
    catch{case _:CancellationException=>()}finally Thread.interrupted()
    var propagated=false
    try AddColumn.transform(new InputStream{def read():Int=throw new IOException("test input failure")},new ByteArrayOutputStream,p)
    catch{case _:IOException=>propagated=true};assert(propagated)
    val inputs=Map("source"->StreamDescriptor(Set("bytes"),Set("text"),certainty="known"))
    Seq("c0","c9-c2","c1-c1025","c1,,c2","c9999999").foreach{c=>rejects(CutColumns.inferOutputs(inputs,mapper.createObjectNode().put("columns",c)))}
    rejects(ChangeCase.inferOutputs(inputs,mapper.createObjectNode().put("casing","invalid")))
    rejects(AddColumn.inferOutputs(inputs,mapper.createObjectNode().put("iterate","true")))
    rejects(AddColumn.inferOutputs(inputs,mapper.createObjectNode().put("unexpected",1)))
    // A repeated 8 KiB field must fail before allocating a multi-megabyte output row.
    rejects(CutColumns.transform(new ByteArrayInputStream(("x"*8192).getBytes(UTF_8)),new ByteArrayOutputStream,mapper.readTree("{\"columns\":\""+Vector.fill(1024)("c1").mkString(",")+"\"}")))
    Thread.currentThread.interrupt()
    try {FastaLength.transform(new ByteArrayInputStream(">id\nACGT".getBytes(UTF_8)),new ByteArrayOutputStream,p);throw new AssertionError("FASTA cancellation ignored")}
    catch{case _:CancellationException=>()}finally Thread.interrupted()
    rejects(FastaFilterLength.inferOutputs(inputs,mapper.readTree("{\"min_length\":10,\"max_length\":2}")))
    rejects(Tail.inferOutputs(inputs,mapper.readTree("{\"lines\":10001}")))
    rejects(FastaToTabular.inferOutputs(inputs,mapper.readTree("{\"description_columns\":0}")))
    rejects(FastaLength.transform(new ByteArrayInputStream("ACGT".getBytes(UTF_8)),new ByteArrayOutputStream,p))
    val large=("x"*1048576+"\n")*9
    rejects(Tail.transform(new ByteArrayInputStream(large.getBytes(UTF_8)),new ByteArrayOutputStream,mapper.readTree("{\"lines\":10}")))
    val fasta=(">large\n"+large)
    rejects(FastaFilterLength.transform(new ByteArrayInputStream(fasta.getBytes(UTF_8)),new ByteArrayOutputStream,p))
    val empty=new ByteArrayOutputStream
    FastaFilterLength.transform(new ByteArrayInputStream(fasta.getBytes(UTF_8)),empty,mapper.readTree("{\"max_length\":1}"));assert(empty.size==0)
    rejects(TabularToFasta.inferOutputs(inputs,mapper.readTree("{\"title_columns\":\"0\"}")))
    rejects(TabularToFasta.inferOutputs(inputs,mapper.readTree("{\"sequence_column\":0}")))
    rejects(SamFilterFlags.inferOutputs(inputs,mapper.readTree("{\"require_flags\":2,\"exclude_flags\":2}")))
    rejects(SamFilterFlags.inferOutputs(inputs,mapper.readTree("{\"require_flags\":65536}")))
    rejects(SamToInterval.inferOutputs(inputs,mapper.readTree("{\"print_all\":\"yes\"}")))
    Seq("4Mgarbage","0M","4I","*").foreach{cigar=>
      val sam=s"r1\t0\tchr1\t1\t20\t$cigar\t*\t0\t0\tACGT\t*"
      rejects(SamToInterval.transform(new ByteArrayInputStream(sam.getBytes(UTF_8)),new ByteArrayOutputStream,p))
    }
    val expandedTitle="x"*20000+"\tACGT"
    rejects(TabularToFasta.transform(new ByteArrayInputStream(expandedTitle.getBytes(UTF_8)),new ByteArrayOutputStream,mapper.createObjectNode().put("title_columns",Vector.fill(128)("1").mkString(","))))
    val positionOverflow="r1\t0\tchr1\t9223372036854775807\t20\t4M\t*\t0\t0\tACGT\t*"
    var overflow=false
    try SamToInterval.transform(new ByteArrayInputStream(positionOverflow.getBytes(UTF_8)),new ByteArrayOutputStream,p) catch{case _:ArithmeticException=>overflow=true};assert(overflow)
    Seq(TabularToFasta,SamFilterFlags,SamToInterval).foreach{op=>
      Thread.currentThread.interrupt()
      try{op.transform(new ByteArrayInputStream(Array.emptyByteArray),new ByteArrayOutputStream,p);throw new AssertionError("cancellation ignored")}
      catch{case _:CancellationException=>()}finally Thread.interrupted()
      var io=false
      try op.transform(new InputStream{def read():Int=throw new IOException("test")},new ByteArrayOutputStream,p)catch{case _:IOException=>io=true};assert(io)
      var utf8=false
      try op.transform(new ByteArrayInputStream(Array(0xff.toByte)),new ByteArrayOutputStream,p)catch{case _:java.nio.charset.MalformedInputException=>utf8=true};assert(utf8)
      rejects(op.transform(new ByteArrayInputStream(("x"*1048577).getBytes(UTF_8)),new ByteArrayOutputStream,p))
    }
    println("PASS tabular/SAM parameters, malformed CIGAR, coordinate overflow, cancellation, I/O and UTF-8/line budgets")
    println("PASS FASTA cancellation, invalid headers/ranges, tail/record budgets and discarded large records")
    println("PASS cancellation, I/O failure propagation, inference validation and output expansion budget")
  }
}
