package xstream.engine

import _root_.xstream.execution.{ItemSourceParameters}

import _root_.xstream.data._

import java.nio.file.{Files,Path}
import scala.jdk.CollectionConverters._
import org.yaml.snakeyaml.Yaml

case class InputBinding(uri: Option[String] = None, task: Option[String] = None, port: String = "result", view: String = "bytes", scan:Option[Scan]=None, selection:Option[com.fasterxml.jackson.databind.JsonNode]=None)
case class Task(id: String, inputs: Map[String,InputBinding], operator: String, params: java.util.Map[String,Object], node: Option[String] = None, title: Option[String] = None, outputPorts:Seq[String]=Seq.empty, proxyPath:Vector[String]=Vector.empty, localOperator:Option[String]=None, description:Option[String]=None, inlineOperator:Option[_root_.xstream.execution.InlineOperator]=None, preparedInline:Option[_root_.xstream.operator.inference.ResolvedOperator]=None)
case class JobPlan(steps: Vector[Task], outputs: Map[String,(String,String)], name:String="Job", inlineOperators:Map[String,_root_.xstream.execution.InlineOperator]=Map.empty, hasInlineDefinitions:Boolean=false, requiredOperators:Set[String]=Set.empty)
object Jobs {
  val ItemSource = "item_source@1.0"
  val ClientSink = "client_sink@1.0"
  def dependencies(t:Task):Vector[String]=t.inputs.values.flatMap(_.task).toVector.distinct
  def roots(plan:JobPlan):Set[String]={val upstream=plan.steps.flatMap(dependencies).toSet;plan.steps.map(_.id).filterNot(upstream).toSet++plan.outputs.values.map(_._1)}
  def reference(value:Any):(String,String)={
    require(value.isInstanceOf[String],"stream binding must be step[:port]")
    val parts=value.toString.split(":",-1);require(parts.length==1 || parts.length==2,"stream binding must be step[:port]")
    val task=parts(0);val port=if(parts.length==1)"result"else parts(1);name(task);name(port);task->port
  }
  def readBinding(t:Task):Option[InputBinding]=if(t.operator!=ItemSource)None else {
    require(t.inputs.isEmpty,"item_source is a zero-input transport source")
    val address=ItemSourceParameters.address(t.params)
    val b=InputBinding(uri=Some(address),view=ItemSourceParameters.view(t.params),scan=ItemSourceParameters.query(t.params),selection=ItemSourceParameters.selection(t.params))
    Some(b)
  }

  def resolveReads(plan:JobPlan,origin:String):JobPlan=plan.copy(steps=plan.steps.map { t=>
    if(t.operator!=ItemSource || !Option(t.params.get("uri")).exists(_.toString.startsWith("context:")))t else {
      val params=new java.util.LinkedHashMap[String,Object](t.params)
      val uri=ItemSourceParameters.address(params,Some(origin));params.put("uri",uri);t.copy(params=params)
    }
  })

  /** Contracting node regions must not turn an acyclic task graph into a cycle. */
  def canGroupByNode(plan:JobPlan,nodes:Map[String,String]):Boolean={
    val edges=plan.steps.flatMap(t=>dependencies(t).map(p=>nodes(p)->nodes(t.id))).filter(e=>e._1!=e._2).distinct
    var left=nodes.values.toSet;var done=Set.empty[String]
    while(left.nonEmpty){val ready=left.filter(n=>edges.filter(_._2==n).forall(e=>done(e._1)));if(ready.isEmpty)return false;done++=ready;left--=ready}
    true
  }

  def authorizeSource(config: EngineEnvironment, task: Task): Unit =
    {
      require(task.inlineOperator.isEmpty || config.localOperatorBase.nonEmpty,"FORBIDDEN: inline operators require a local engine")
      require(task.localOperator.isEmpty || config.localOperatorBase.nonEmpty,"FORBIDDEN: local operators require a local engine")
    }

  private def name(s: String): Unit = require(s.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"),"invalid step/port name")
  private def mapping(value: Any, label: String): Map[String,Object] = {
    require(value.isInstanceOf[java.util.Map[_,_]],s"$label must be a mapping")
    scala.collection.immutable.VectorMap.from(value.asInstanceOf[java.util.Map[String,Object]].asScala)
  }
  def validate(t: Task): Unit = {
    t.title.foreach(v=>require(v.trim.nonEmpty && v.length<=256 && !v.exists(_.isControl),"title must contain 1 to 256 characters without control characters"))
    t.description.foreach(v=>require(v.length<=8192 && !v.exists(c=>c.isControl && c!='\n' && c!='\r' && c!='\t'),"description must contain at most 8192 characters"))
    name(t.id); t.inputs.keys.foreach(name)
    require(t.localOperator.isEmpty || t.operator.isEmpty,"local operator cannot specify another operator")
    t.localOperator.foreach(p=>require(p.trim.nonEmpty && !p.exists(_.isControl),"invalid local operator path"))
    require(t.inlineOperator.isDefined || t.localOperator.isDefined || t.operator.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*@[A-Za-z0-9_-][A-Za-z0-9_.-]*"),"operator must be name@version")
    require(t.params != null,"params must be a mapping")
    t.inputs.values.foreach { b =>
      require(b.uri.isDefined != b.task.isDefined,"input requires exactly one of uri or task")
      require(b.view.matches("[a-z][a-z0-9_-]*"),"invalid input view")
      name(b.port)
      b.task.foreach(name)
      b.uri.foreach { u => ItemSourceParameters.address(java.util.Map.of[String,Object]("uri",u)); () }
    }
    readBinding(t)
    t.node.foreach(n=>require(n.nonEmpty && n.length<=2048 && !n.exists(_.isControl),"invalid execution target reference"))
  }
  def load(path: Path): JobPlan = parse(Files.readString(path))
  def parse(text: String): JobPlan = {
    require(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024*1024,"job exceeds 1 MiB")
    val options=new org.yaml.snakeyaml.LoaderOptions(); options.setAllowDuplicateKeys(false)
    val raw=try _root_.xstream.util.Json.mapper.convertValue(_root_.xstream.util.YamlDocument.parse(text),classOf[Object])catch{case e:org.yaml.snakeyaml.error.YAMLException=>throw new IllegalArgumentException("invalid job YAML",e)}
    val doc=mapping(raw,"job")
    require(doc.keySet.subsetOf(Set("name","steps","designer","operators")) && doc.contains("name"),"invalid job settings")
    require(doc.get("steps").exists(_.isInstanceOf[java.util.List[_]]),"steps must be a list")
    val inlineOperators=_root_.xstream.execution.InlineOperators.definitions(_root_.xstream.util.YamlDocument.parse(text))
    val steps=doc("steps").asInstanceOf[java.util.List[Object]].asScala.map { raw =>
      val m=mapping(raw,"task")
      require(m.keySet.subsetOf(Set("id","title","description","inputs","operator","params","node")) && Set("id","inputs","operator").subsetOf(m.keySet),"invalid step settings")
      val inputs=mapping(m("inputs"),"inputs").map { case (port,raw) =>
        val (task,output)=reference(raw)
        port -> InputBinding(task=Some(task),port=output)
      }
      val params=new java.util.LinkedHashMap[String,Object](); m.get("params").foreach(v => mapping(v,"params").foreach{case(k,v)=>params.put(k,v)})
      val declaration=if(m("operator").isInstanceOf[java.util.Map[_,_]])Some(mapping(m("operator"),"operator"))else None
      val local=declaration.filter(_.contains("local")).map { d=>require(d.keySet==Set("local") && d("local").isInstanceOf[String],"local operator requires only a string local reference");d("local").toString }
      val inline=declaration.filter(_.contains("inline")).map { d=>
        require(d.keySet==Set("inline") && d("inline").isInstanceOf[String],"inline reference requires only a string name")
        inlineOperators.getOrElse(d("inline").toString,throw new IllegalArgumentException("unknown inline operator: "+d("inline")))
      }
      require(declaration.isEmpty || local.nonEmpty || inline.nonEmpty,"operator must be name@version, {local: reference}, or {inline: name}")
      require(m.get("title").forall(_.isInstanceOf[String]),"title must be a string")
      val t=Task(m("id").toString,inputs,if(inline.isDefined)"inline:"+inline.get.name else if(local.isDefined)""else m("operator").toString,params,m.get("node").map(_.toString),m.get("title").map(_.toString),localOperator=local,description=m.get("description").map{v=>require(v.isInstanceOf[String],"description must be a string");v.toString},inlineOperator=inline);validate(t);t
    }.toVector
    require(steps.nonEmpty && steps.size <= 64 && steps.map(_.id).distinct.size == steps.size,"empty, oversized, or duplicate step list")
    val ids=steps.map(_.id).toSet
    steps.foreach(t=>dependencies(t).foreach(id=>require(ids(id),"unknown upstream step")))
    var left=steps; var done=Set.empty[String]; val result=Vector.newBuilder[Task]
    while(left.nonEmpty) {
      val ready=left.filter(t=>dependencies(t).forall(done))
      require(ready.nonEmpty,"cyclic step DAG")
      ready.foreach(t=>{result+=t;done+=t.id});left=left.filterNot(ready.contains)
    }
    val clientSinks=steps.filter(_.operator==ClientSink)
    require(clientSinks.size<=1,"Job accepts one client_sink step")
    clientSinks.foreach { sink=>
      require(sink.inputs.nonEmpty && sink.params.isEmpty,"client_sink requires input ports and no parameters")
      require(!steps.exists(t=>dependencies(t).contains(sink.id)),"client_sink must terminate its branch")
    }
    val outputs=clientSinks.headOption.map(_.inputs.map {case(label,b)=>label->(b.task.get,b.port)}).getOrElse(Map.empty[String,(String,String)])
    val executionSteps=result.result().filterNot(_.operator==ClientSink)
    require(doc("name").isInstanceOf[String] && doc("name").toString.trim.nonEmpty && doc("name").toString.length<=256 && !doc("name").toString.exists(_.isControl),"name must contain 1 to 256 characters without control characters")
    JobPlan(executionSteps,outputs,doc("name").toString,inlineOperators,doc.contains("operators"),clientSinks.map(_.operator).toSet)
  }
}
