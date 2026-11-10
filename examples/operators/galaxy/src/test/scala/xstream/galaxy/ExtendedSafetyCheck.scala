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
   def invoke(input:InputStream,output:OutputStream,parameters:com.fasterxml.jackson.databind.JsonNode):Unit={val extra=f.path("inputs").fields.asScala.map(e=>e.getKey->new ByteArrayInputStream(e.getValue.asText.getBytes(UTF_8))).toMap;op.transformInputs(extra+("source"->input),output,parameters)}
   invoke(new ByteArrayInputStream(data),out,params);assert(out.toString("UTF-8")==f.path("expected").asText,entry.getKey+" actual="+out.toString("UTF-8"))
   def fails(body: => Unit):Unit={var failed=false;try body catch{case _:IllegalArgumentException=>failed=true};assert(failed,entry.getKey)}
   fails(invoke(new ByteArrayInputStream(data),new ByteArrayOutputStream,mapper.readTree("{\"unsupported\":1}")))
   Thread.currentThread.interrupt()
   try{invoke(new ByteArrayInputStream(data),new ByteArrayOutputStream,params);throw new AssertionError("cancellation ignored")}catch{case _:CancellationException=>()}finally Thread.interrupted()
   var io=false;try invoke(new InputStream{def read():Int=throw new IOException("input")},new ByteArrayOutputStream,params)catch{case _:IOException=>io=true};assert(io)
   io=false;try invoke(new ByteArrayInputStream(data),new OutputStream{def write(b:Int):Unit=throw new IOException("output")},params)catch{case _:IOException=>io=true};assert(io)
   var utf8=false;try invoke(new ByteArrayInputStream(Array(0xff.toByte)),new ByteArrayOutputStream,params)catch{case _:java.nio.charset.MalformedInputException=>utf8=true};assert(utf8)
   fails(invoke(new ByteArrayInputStream(("A"*1048577).getBytes(UTF_8)),new ByteArrayOutputStream,params))
   if(entry.getKey=="emboss_shuffleseq"){
    val input=">shuffle\nACGTACGTTTGGCCAA\n".getBytes(UTF_8);val first=new ByteArrayOutputStream;val second=new ByteArrayOutputStream
    invoke(new ByteArrayInputStream(input),first,params);invoke(new ByteArrayInputStream(input),second,params)
    assert(first.toByteArray.toVector==second.toByteArray.toVector,"shuffle seed differs")
    assert(first.toString("UTF-8").split("\n")(1).sorted=="ACGTACGTTTGGCCAA".sorted,"shuffle changes composition");total+=2
   }
   total+=7;println("PASS fixture, validation, cancellation, I/O, encoding and line budget: "+entry.getKey)
  }
  val regressions=mapper.readTree(Files.readString(Path.of("extended-regressions.json")))
  Seq("positive","negative").foreach{kind=>regressions.path(kind).elements.asScala.foreach{c=>
   val spec=specs.path(c.path("operator").asText);val op=Class.forName(spec.path("entrypoint").asText+"$").getField("MODULE$").get(null).asInstanceOf[ExtendedOperator]
   val out=new ByteArrayOutputStream;var failed=false
   val extra=c.path("inputs").fields.asScala.map(e=>e.getKey->new ByteArrayInputStream(e.getValue.asText.getBytes(UTF_8))).toMap
   try op.transformInputs(extra+("source"->new ByteArrayInputStream(c.path("data").asText.getBytes(UTF_8))),out,c.path("params"))catch{case _:IllegalArgumentException=>failed=true}
   def equivalent(actual:String,expected:String):Boolean={
    if(c.has("alignment_score")){
     val lines=actual.split("\n");val score="score=([-0-9.]+)".r.findFirstMatchIn(actual).map(_.group(1).toDouble)
     val rows=lines.filterNot(_.startsWith(">"));val originalA=c.path("data").asText.split("\n").filterNot(_.startsWith(">")).mkString;val originalB=c.path("inputs").path("reference").asText.split("\n").filterNot(_.startsWith(">")).mkString
     if(rows.length!=2||rows(0).length!=rows(1).length||score.isEmpty)false else{
      val a=rows(0);val b=rows(1);var calculated=0d;var gap=0;var valid=true
      a.indices.foreach{i=>if(a(i)=='-'&&b(i)=='-')valid=false;val state=if(a(i)=='-')1 else if(b(i)=='-')2 else 0;if(state==0)calculated+= (if(a(i)==b(i))2 else -1)else calculated-= (if(state==gap)1 else 3);gap=state}
      val local=c.path("operator").asText=="emboss_water";val aa=a.filterNot(_=='-');val bb=b.filterNot(_=='-')
      valid&&math.abs(calculated-c.path("alignment_score").asDouble)<1e-6&&math.abs(score.get-calculated)<1e-6&&(if(local)originalA.contains(aa)&&originalB.contains(bb)else originalA==aa&&originalB==bb)
     }
    }else if(!c.has("numeric_tolerance"))actual==expected else{
     val a=actual.split("[\t\n]",-1);val e=expected.split("[\t\n]",-1);a.length==e.length&&a.zip(e).forall{case(x,y)=>if(x==y)true else try{val u=x.toDouble;val v=y.toDouble;u.isFinite&&v.isFinite&&math.abs(u-v)<=c.path("numeric_tolerance").asDouble}catch{case _:NumberFormatException=>false}}
    }
   }
   if(kind=="positive")assert(!failed&&equivalent(out.toString("UTF-8"),c.path("expected").asText),c.path("operator").asText+" actual="+out.toString("UTF-8")+" expected="+c.path("expected").asText)
   else assert(failed,"malformed input accepted: "+c)
   total+=1
  }}
  println(s"PASS $total extended adapter checks, including upstream golden fixtures")
 }
}
