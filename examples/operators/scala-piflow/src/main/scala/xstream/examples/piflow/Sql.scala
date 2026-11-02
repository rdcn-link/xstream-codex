package xstream.examples.piflow

import java.sql.{Connection,DriverManager}
import com.fasterxml.jackson.databind.JsonNode
import org.apache.arrow.vector.types.pojo.{ArrowType,Schema}
import org.apache.arrow.vector.types.FloatingPointPrecision
import xstream.execution.{StreamDescriptor,InferenceResult}

private[piflow] object Sql {
  import Support._
  def quote(s:String):String="\""+s.replace("\"","\"\"")+"\""
  def database(s:Schema,view:String)(use:Connection=>Unit):Unit={
    unique(s);fields(s).foreach(scalar);require(view.nonEmpty,"viewName must be nonempty")
    Class.forName("org.sqlite.JDBC")
    val c=DriverManager.getConnection("jdbc:sqlite::memory:")
    try {val st=c.createStatement();try {st.execute("PRAGMA temp_store=MEMORY");st.execute("CREATE TABLE "+quote(view)+names(s).map(quote).mkString("(",",",")"))}finally st.close();use(c)}finally c.close()
  }
  def insert(c:Connection,s:Schema,view:String,rows:Iterator[Vector[AnyRef]]):Unit={
    val stmt=c.prepareStatement("INSERT INTO "+quote(view)+" VALUES "+names(s).map(_=>"?").mkString("(",",",")"))
    c.setAutoCommit(false)
    try {rows.foreach{row=>check();row.zipWithIndex.foreach{case(x,i)=>stmt.setObject(i+1,x match {case b:java.lang.Boolean=>if(b)Integer.valueOf(1)else Integer.valueOf(0);case v:java.time.temporal.TemporalAccessor=>v.toString;case d:java.math.BigDecimal=>d.toString;case v=>v})};stmt.executeUpdate()};c.commit()}finally stmt.close()
  }
  def query(c:Connection,sql:String)(consume:(Vector[String],Iterator[Vector[AnyRef]])=>Unit):Unit={
    require(sql.trim.matches("(?is)^(select|with)\\b.*"),"only read-only SELECT/WITH queries are allowed")
    val lock=c.createStatement();try lock.execute("PRAGMA query_only=ON")finally lock.close()
    var ticks=0
    org.sqlite.ProgressHandler.setHandler(c,1000,new org.sqlite.ProgressHandler {
      def progress():Int={ticks+=1;if(Thread.currentThread.isInterrupted || ticks>10000)1 else 0}
    })
    val stmt=c.createStatement()
    try {
      val rs=stmt.executeQuery(sql)
      try {
        val meta=rs.getMetaData;val ns=(1 to meta.getColumnCount).map(meta.getColumnLabel).toVector
        require(ns.distinct.size==ns.size,"query must return unique column names")
        val it=new Iterator[Vector[AnyRef]] {
          private var ready=false;private var ended=false
          def hasNext:Boolean={check();if(!ready && !ended){ready=rs.next();ended= !ready};ready}
          def next():Vector[AnyRef]={if(!hasNext)throw new NoSuchElementException;ready=false;(1 to ns.size).map(rs.getObject).toVector}
        };consume(ns,it)
      }finally rs.close()
    }finally {stmt.close();org.sqlite.ProgressHandler.clearHandler(c)}
  }
  def resultSchema(ns:Vector[String],rows:Vector[Vector[AnyRef]],source:Schema):Schema=schema(ns.zipWithIndex.map{case(n,i)=>
    val values=rows.iterator.map(_(i)).filter(_!=null).toVector
    val t:ArrowType=if(values.isEmpty)Option(source.findField(n)).map(_.getType).getOrElse(ArrowType.Null.INSTANCE)
      else if(values.forall(x=>x.isInstanceOf[java.lang.Integer]||x.isInstanceOf[java.lang.Long]))new ArrowType.Int(64,true)
      else if(values.forall(_.isInstanceOf[Number]))new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
      else if(values.forall(_.isInstanceOf[Array[Byte]]))ArrowType.Binary.INSTANCE
      else {require(values.forall(_.isInstanceOf[String]),"SQL output has mixed incompatible types");ArrowType.Utf8.INSTANCE}
    field(n,t)
  })
}
class FilterOperator extends StreamOperator {
  import Support._
  protected def validate(p:JsonNode):Unit={params(p,Set("condition"));require(text(p,"condition").trim.nonEmpty,"condition must be nonempty")}
  protected def infer(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={inputs("source").schema.foreach(s=>fields(s).foreach(scalar));descriptor(inputs("source").schema)}
  private[piflow] def execute(inputs:Map[String,Source],out:Sink,p:JsonNode):Unit={
    val in=inputs("source");val s=in.schema;val reserved="__dacp_row_index";require(!names(s).contains(reserved),"reserved SQL column: "+reserved)
    val indexed=schema(fields(s):+field(reserved,new ArrowType.Int(64,true)));val send=out.records(s)
    in.records(root=>Sql.database(indexed,"source"){c=>
      Sql.insert(c,indexed,"source",rows(root).iterator.zipWithIndex.map{case(r,i)=>r:+Integer.valueOf(i)})
      Sql.query(c,"SELECT "+Sql.quote(reserved)+" FROM source WHERE ("+text(p,"condition")+")"){(_,result)=>projected(root,s,result.map(_.head.asInstanceOf[Number].intValue).toVector,send)}
    })
  }
}
object Filter extends FilterOperator
class ExecuteSqlOperator extends StreamOperator {
  import Support._
  protected def validate(p:JsonNode):Unit={params(p,Set("viewName","sql"));require(text(p,"viewName").nonEmpty,"viewName must be nonempty");text(p,"sql")}
  protected def infer(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={
    // SQLite expression result types can depend on data. Do not declare guessed types.
    inputs("source").schema.foreach(s=>fields(s).foreach(scalar));descriptor(None)
  }
  private[piflow] def execute(inputs:Map[String,Source],out:Sink,p:JsonNode):Unit={
    val in=inputs("source");val s=in.schema
    Sql.database(s,text(p,"viewName")){c=>
      var inputSize=0L
      in.records(root=>{val batch=rows(root);batch.foreach{row=>inputSize+=estimated(row);require(inputSize<=MaxBytes,"SQL input exceeds 128 MiB")};Sql.insert(c,s,text(p,"viewName"),batch.iterator)})
      Sql.query(c,text(p,"sql")){(ns,result)=>
        var size=0L
        val data=result.map{row=>size+=estimated(row);require(size<=MaxBytes,"SQL output exceeds 128 MiB");row}.toVector
        val target=Sql.resultSchema(ns,data,s);emitRows(target,data.iterator,out.records(target))
      }
    }
  }
}
object ExecuteSql extends ExecuteSqlOperator
