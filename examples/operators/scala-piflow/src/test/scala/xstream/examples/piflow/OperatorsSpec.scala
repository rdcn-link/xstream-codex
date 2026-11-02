package xstream.examples.piflow

import org.scalatest.funsuite.AnyFunSuite
import xstream.execution.{StreamDescriptor,OutputSample,PreviewLimits}
import org.apache.arrow.vector.types.pojo.ArrowType
import Support._
import scala.jdk.CollectionConverters._

class OperatorsSpec extends AnyFunSuite {
  def p(s:String)=mapper.readTree(s)
  val limits=PreviewLimits(100,65536)
  def records(cols:Vector[String],data:String)=StreamDescriptor(Set("records"),Set("arrow"),Some(schema(cols.map(n=>field(n,ArrowType.Utf8.INSTANCE)))),"known",sample=Some(OutputSample("records","arrow",p(data).elements().asScala.toVector)))
  val source=records(Vector("id","v"),"""[{"id":"a","v":"2"},{"id":"b","v":"4"},{"id":"c","v":null}]""")
  def preview(op:StreamOperator,in:Map[String,StreamDescriptor],params:String)=op.previewOutputs(in,p(params),limits)("result")
  test("CSV handles UTF-8, quoted delimiters, embedded newlines, trailing empty cells and batches") {
    val text="id,v\r\na,\"one,two\"\r\nb,\"three\nfour\"\r\nc,\r\n"
    val d=StreamDescriptor(Set("bytes"),Set("csv"),certainty="known",sample=Some(OutputSample("bytes","csv",data=text.getBytes("UTF-8").toVector)))
    val rows=preview(CsvParser,Map("source"->d),"{}").rows
    assert(rows.size==3);assert(rows(0).path("v").asText=="one,two");assert(rows(1).path("v").asText=="three\nfour");assert(rows(2).path("v").asText=="")
    assert(CsvParser.inferOutputs(Map("source"->d),p("{}")).outputs("result").schema.get.getFields.size==2)
    val result=preview(CsvSave,Map("source"->source),"{}");assert(new String(result.data.toArray,"UTF-8")=="id,v\na,2\nb,4\nc,\n")
  }
  test("headerless CSV schema and malformed row rejection") {
    val d=StreamDescriptor(Set("bytes"),Set("csv"),certainty="known",sample=Some(OutputSample("bytes","csv",data="a,2\nb\n".getBytes.toVector)))
    val params=p("""{"header":false,"schema":"id,v"}""")
    assert(CsvParser.inferOutputs(Map("source"->d),params).outputs("result").schema.nonEmpty)
    intercept[IllegalArgumentException]{CsvParser.previewOutputs(Map("source"->d),params,limits)}
  }
  test("filter preserves types and nulls while using SQLite predicate semantics") {
    val out=preview(Filter,Map("source"->source),"""{"condition":"CAST(v AS REAL) > 2 OR v IS NULL"}""")
    assert(out.rows.map(_.path("id").asText)==Vector("b","c"));assert(out.rows(1).path("v").isNull)
    assert(Filter.inferOutputs(Map("source"->source),p("""{"condition":"1"}""")).outputs("result").schema==source.schema)
  }
  test("SQL aggregation, empty outputs, duplicate names and writes") {
    val in=Map("source"->source)
    val out=preview(ExecuteSql,in,"""{"viewName":"t","sql":"SELECT COUNT(*) AS n, SUM(CAST(v AS REAL)) AS total FROM t"}""")
    assert(out.rows.head.path("n").asInt==3);assert(out.rows.head.path("total").asDouble==6)
    assert(preview(ExecuteSql,in,"""{"viewName":"t","sql":"SELECT * FROM t WHERE 0"}""").rows.isEmpty)
    intercept[IllegalArgumentException]{preview(ExecuteSql,in,"""{"viewName":"t","sql":"DELETE FROM t"}""")}
    intercept[IllegalArgumentException]{preview(ExecuteSql,in,"""{"viewName":"t","sql":"SELECT id,id FROM t"}""")}
  }
  test("merge union schema fills missing columns and keeps left then right order") {
    val right=records(Vector("id","w"),"""[{"id":"a","w":"x"}]""")
    val in=Map("left"->source,"right"->right)
    val inferred=Merge.inferOutputs(in,p("{}")).outputs("result").schema.get
    assert(names(inferred)==Vector("id","v","w"));assert(fields(inferred).forall(_.isNullable))
    val out=preview(Merge,in,"{}");assert(out.rows.size==4);assert(out.rows.head.path("w").isNull);assert(out.rows.last.path("v").isNull)
  }
  test("join inference and all modes handle unmatched rows, duplicate keys, null keys and suffixes") {
    val left=records(Vector("id","v"),"""[{"id":"a","v":"1"},{"id":"a","v":"2"},{"id":"b","v":"3"},{"id":null,"v":"4"}]""")
    val right=records(Vector("id","v"),"""[{"id":"a","v":"x"},{"id":"c","v":"y"},{"id":null,"v":"z"}]""")
    val in=Map("left"->left,"right"->right)
    for((mode,count)<-Vector("inner"->2,"left"->4,"right"->4,"full_outer"->6)) {
      val params=s"""{"joinMode":"$mode","correlationColumn":"id"}"""
      val s=Join.inferOutputs(in,p(params)).outputs("result").schema.get
      assert(names(s)==Vector("id","v","v_right"));assert(preview(Join,in,params).rows.size==count)
    }
  }
  test("preview independently respects row and byte budgets and inherited truncation") {
    val out=Filter.previewOutputs(Map("source"->source),p("""{"condition":"1"}"""),PreviewLimits(1,256))("result")
    assert(out.rows.size<=1 && out.truncated);assert(xstream.execution.OutputSamples.size(out)<=256)
  }
  test("CSV processing publishes bounded batches before consuming the complete source") {
    val raw=("id,v\n"+(0 until 1000).map(i=>s"$i,value$i\n").mkString).getBytes("UTF-8")
    var offset=0;var batches=Vector.empty[Int];var firstOffset=0
    val input=new java.io.InputStream {
      def read():Int=if(offset==raw.length)-1 else {val b=raw(offset)&255;offset+=1;b}
      override def read(b:Array[Byte],start:Int,length:Int):Int={
        if(offset==raw.length)-1 else {val n=math.min(math.min(length,64),raw.length-offset);System.arraycopy(raw,offset,b,start,n);offset+=n;n}
      }
    }
    CsvParser.execute(Map("source"->new Source {
      def schema=throw new UnsupportedOperationException
      def records(f:org.apache.arrow.vector.VectorSchemaRoot=>Unit):Unit=throw new UnsupportedOperationException
      def bytes=input
    }),new Sink {
      def records(s:org.apache.arrow.vector.types.pojo.Schema):org.apache.arrow.vector.VectorSchemaRoot=>Unit=r=>{
        if(batches.isEmpty)firstOffset=offset
        batches=batches:+r.getRowCount
      }
      def bytes()=throw new UnsupportedOperationException
    },p("{}"))
    assert(batches.sum==1000 && batches.forall(_<=128))
    assert(firstOffset<raw.length)
  }
  test("incompatible merge fields and ambiguous join suffixes fail during inference") {
    val ints=source.copy(schema=Some(schema(Vector(field("id",new ArrowType.Int(64,true)),field("v",ArrowType.Utf8.INSTANCE)))))
    intercept[IllegalArgumentException]{Merge.inferOutputs(Map("left"->source,"right"->ints),p("{}"))}
    val ambiguous=records(Vector("id","v","v_right"),"[]")
    intercept[IllegalArgumentException]{Join.inferOutputs(Map("left"->ambiguous,"right"->source),p("""{"joinMode":"inner","correlationColumn":"id"}"""))}
  }

}
