package xstream.galaxy

import java.io.{InputStream,OutputStream,InputStreamReader,OutputStreamWriter,BufferedReader,BufferedWriter}
import java.nio.charset.{StandardCharsets,CodingErrorAction}
import java.util.Locale
import java.util.concurrent.CancellationException
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._
import xstream.operator.{Operator,ByteStreamOperator,OperatorContext}
import xstream.execution.{StreamDescriptor,InferenceResult,OutputSample,PreviewLimits}

/** Galaxy text tools adapted to bounded, cancellable UTF-8 streams. See NOTICE. */
abstract class GalaxyOperator(val operation:String) extends Operator {
  private val delimiters=Map("tab"->"\t","comma"->",","dash"->"-","underscore"->"_","pipe"->"\\|","dot"->"\\.","whitespace"->"(?U)\\s+","space"->" ","colon"->":")
  private def text(p:JsonNode,key:String,default:String):String={
    val n=p.get(key);require(n==null || n.isTextual,key+" must be a string")
    val s=if(n==null)default else n.asText;require(s.length<=4096,key+" is too long");s
  }
  private def columns(p:JsonNode):Vector[Int]={
    val s=text(p,"columns","c1").replaceAll("\\s+","")
    val result=s.split(",",-1).toVector.flatMap{c=>
      require(c.matches("(?i)c[1-9][0-9]{0,5}(-c[1-9][0-9]{0,5})?"),"columns must use c1,c2 or c2-c4")
      val ends=c.toLowerCase(Locale.ROOT).split("-c").map(_.stripPrefix("c").toInt)
      val end=ends.last;require(end>=ends.head && end-ends.head<1024,"invalid or excessive column range")
      (ends.head to end).map(_-1)
    }
    require(result.nonEmpty && result.size<=1024,"select between 1 and 1024 columns");result
  }
  private def allowed:Set[String]=operation match {
    case "change_case"=>Set("columns","delimiter","casing")
    case "cut_columns"=>Set("columns","delimiter")
    case "merge_columns"=>Set("columns")
    case "add_column"=>Set("value","iterate")
    case _=>Set("character")
  }
  def validate(p:JsonNode):Unit={
    require(p!=null && p.isObject,"parameters must be an object")
    require(p.fieldNames.asScala.forall(allowed),"unknown parameter")
    if(Set("change_case","cut_columns","merge_columns")(operation))columns(p)
    if(Set("change_case","cut_columns")(operation))require(Set("tab","comma","dash","underscore","pipe","dot","whitespace")(text(p,"delimiter","tab")),"unsupported delimiter")
    if(operation=="change_case")require(Set("upper","lower")(text(p,"casing","upper")),"casing must be upper or lower")
    if(Set("condense_characters","convert_delimiters")(operation))require(delimiters.contains(text(p,"character","tab")),"unsupported character")
    if(operation=="add_column"){
      val v=text(p,"value","1");require(!v.exists(c=>c=='\n'||c=='\r'||c=='\t'),"value cannot contain tabs or line breaks")
      require(!p.has("iterate") || p.get("iterate").isBoolean,"iterate must be boolean")
    }
  }
  private def boundedJoin(fields:Seq[String],separator:String):String={
    require(fields.foldLeft(0L)(_+_.length)+math.max(0,fields.size-1).toLong*separator.length<=4194304,"output line exceeds 4 MiB characters");fields.mkString(separator)
  }
  private def cancelled():Unit=if(Thread.currentThread.isInterrupted)throw new CancellationException("operator cancelled")
  private val delegate=new ByteStreamOperator {
    override def outputFormat:String=if(operation=="condense_characters")"text"else "tsv"
    def transform(input:InputStream,output:OutputStream,p:JsonNode):Unit={
      validate(p)
      val reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),8192)
      val writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),8192)
      val cols=if(Set("change_case","cut_columns","merge_columns")(operation))columns(p)else Vector.empty
      val delimiter=delimiters(text(p,"delimiter","tab"))
      val character=text(p,"character","tab")
      val pattern=java.util.regex.Pattern.compile("(?:"+delimiters(character)+")+")
      val replacement=character match {case "tab"=>"\t";case "whitespace"|"space"=>" ";case "dot"=>".";case "comma"=>",";case "dash"=>"-";case "underscore"=>"_";case "pipe"=>"|";case "colon"=>":"}
      val value=text(p,"value","1");var counter=if(value.matches("[0-9]+"))BigInt(value)else BigInt(0)
      val numeric=value.matches("[0-9]+")
      def emit(line:String):Unit={
        cancelled()
        val result:Option[String]=operation match {
          case "change_case"=>val selected=cols.toSet;Some(line.split(delimiter).zipWithIndex.map{case(v,i)=>if(selected(i)){if(text(p,"casing","upper")=="upper")v.toUpperCase(Locale.ROOT)else v.toLowerCase(Locale.ROOT)}else v}.mkString("\t"))
          case "condense_characters"=>Some(pattern.matcher(line).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement)))
          case "convert_delimiters"=>Some(pattern.matcher(line.strip()).replaceAll("\t"))
          case "cut_columns"=>if(line.startsWith("#"))None else {val fields=if(line.isEmpty)Array.empty[String]else line.split(delimiter);Some(boundedJoin(cols.map(i=>fields.lift(i).getOrElse(".")),"\t"))}
          case "merge_columns"=>if(line.isEmpty || line.startsWith("#"))Some(line)else {val fields=line.split("\t",-1);Some(boundedJoin(Vector(line,boundedJoin(cols.flatMap(fields.lift),"")),"\t"))}
          case "add_column"=>val next=if(!p.path("iterate").asBoolean(false))value else if(numeric)counter.toString else value+"-"+counter;counter+=1;Some(line+"\t"+next)
        }
        result.foreach{s=>writer.write(s);writer.write('\n')}
      }
      // readLine() can allocate an unbounded line. Enforce the limit before append.
      val line=new java.lang.StringBuilder();var ch=reader.read();var afterCR=false;var count=0
      while(ch != -1){
        if((count & 4095)==0)cancelled();count+=1
        if(ch=='\n' && afterCR)afterCR=false
        else if(ch=='\r'||ch=='\n'){emit(line.toString);line.setLength(0);afterCR=ch=='\r'}
        else {afterCR=false;require(line.length<1048576,"line exceeds 1 MiB characters");line.append(ch.toChar)}
        ch=reader.read()
      }
      if(line.length>0)emit(line.toString)
      writer.flush()
    }
  }
  def transform(input:InputStream,output:OutputStream,p:JsonNode):Unit=delegate.transform(input,output,p)
  def inferOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode):InferenceResult={validate(p);delegate.inferOutputs(inputs,p)}
  def previewOutputs(inputs:Map[String,StreamDescriptor],p:JsonNode,limits:PreviewLimits):Map[String,OutputSample]={validate(p);delegate.previewOutputs(inputs,p,limits)}
  def process(ctx:OperatorContext):Unit={validate(ctx.params);delegate.process(ctx)}
}
object ChangeCase extends GalaxyOperator("change_case")
object CondenseCharacters extends GalaxyOperator("condense_characters")
object ConvertDelimiters extends GalaxyOperator("convert_delimiters")
object CutColumns extends GalaxyOperator("cut_columns")
object MergeColumns extends GalaxyOperator("merge_columns")
object AddColumn extends GalaxyOperator("add_column")
