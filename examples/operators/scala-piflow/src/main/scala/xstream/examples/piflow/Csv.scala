package xstream.examples.piflow

import java.io.{InputStreamReader,OutputStreamWriter}
import java.nio.charset.{StandardCharsets,CodingErrorAction}
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.vector.types.pojo.{ArrowType,Schema}
import org.apache.commons.csv.{CSVFormat,CSVPrinter}
import xstream.execution.{StreamDescriptor,InferenceResult}
import scala.jdk.CollectionConverters._

private[piflow] object Csv {
  import Support._
  def format(p:JsonNode):CSVFormat={
    val delimiter=text(p,"delimiter",",");require(delimiter.length==1,"delimiter must be one character")
    CSVFormat.DEFAULT.builder().setDelimiter(delimiter.charAt(0)).setIgnoreEmptyLines(false).setRecordSeparator("\n").get()
  }
  def validate(p:JsonNode,parser:Boolean):Unit={params(p,Set.empty,if(parser)Set("header","delimiter","schema")else Set("header","delimiter"));bool(p,"header",true);format(p);if(parser)text(p,"schema","")}
}
class CsvParserOperator extends StreamOperator {
  import Support._
  override protected def bytesInput=true
  protected def validate(p:JsonNode):Unit=Csv.validate(p,true)
  private def read(in:java.io.InputStream,p:JsonNode)(consume:(Schema,Iterator[Vector[AnyRef]])=>Unit):Unit={
    val decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val reader=new InputStreamReader(in,decoder);val parser=Csv.format(p).parse(reader)
    try {
      val rows=parser.iterator().asScala
      val first=if(rows.hasNext)Some(rows.next().asScala.toVector)else None
      val declared=text(p,"schema","");val header=bool(p,"header",true)
      val ns=if(header)first.getOrElse(columns(declared))else if(declared.nonEmpty)columns(declared)else first.toVector.flatMap(_.indices.map(i=>"_"+i))
      require(ns.nonEmpty && ns.forall(_.nonEmpty) && ns.distinct.size==ns.size,"CSV requires unique, nonempty columns")
      val s=schema(ns.map(n=>field(n,ArrowType.Utf8.INSTANCE)))
      val values=(if(header)Iterator.empty else first.iterator)++rows.map(_.asScala.toVector)
      consume(s,values.map{r=>check();require(r.size==ns.size,"CSV row width differs from schema");r.map(_.asInstanceOf[AnyRef])})
    }finally parser.close()
  }
  protected def infer(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={
    val declared=text(p,"schema","")
    if(!bool(p,"header",true) && declared.nonEmpty)descriptor(Some(schema(columns(declared).map(n=>field(n,ArrowType.Utf8.INSTANCE)))))
    else inputs("source").sample match {
      case Some(s) if s.data.nonEmpty=>
        var result:Schema=null
        read(new java.io.ByteArrayInputStream(s.data.toArray),p){(schema,_)=>result=schema}
        descriptor(Option(result))
      case _=>descriptor(None)
    }
  }
  private[piflow] def execute(inputs:Map[String,Source],out:Sink,p:JsonNode):Unit=read(inputs("source").bytes,p){(s,rows)=>emitRows(s,rows,out.records(s))}
}
object CsvParser extends CsvParserOperator
class CsvSaveOperator extends StreamOperator {
  import Support._
  protected def validate(p:JsonNode):Unit=Csv.validate(p,false)
  protected def infer(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={inputs("source").schema.foreach(s=>fields(s).foreach(scalar));InferenceResult(Map("result"->StreamDescriptor(Set("bytes"),Set("csv"),certainty="known")))}
  private[piflow] def execute(inputs:Map[String,Source],out:Sink,p:JsonNode):Unit={
    val in=inputs("source");val s=in.schema;fields(s).foreach(scalar)
    val writer=new OutputStreamWriter(out.bytes(),StandardCharsets.UTF_8);val printer=new CSVPrinter(writer,Csv.format(p))
    if(bool(p,"header",true))printer.printRecord(names(s).asJava)
    in.records(r=>{rows(r).foreach(row=>printer.printRecord(row.map{case null=>"";case b:java.lang.Boolean=>if(b)"True"else "False";case x=>x}.asJava));printer.flush()})
    printer.flush()
  }
}
object CsvSave extends CsvSaveOperator
