package xstream.runner

import _root_.xstream.engine.{Jobs,LocalEngine}
import _root_.xstream.execution.{StreamCancel,StreamProtocol}

import _root_.xstream.util.WorkflowVariables

import _root_.xstream.data._

import _root_.xstream.util.{Json,YamlDocument}
import java.nio.file.{Files,Path}
import scala.util.Using
import scala.jdk.CollectionConverters._

/** Standalone launcher: no server, listening port, server certificate or dashboard. */
object Main {
  private def sampleValue(value:Any):com.fasterxml.jackson.databind.JsonNode=value match {
    case null=>Json.mapper.nullNode()
    case v:org.apache.arrow.vector.util.Text=>Json.mapper.getNodeFactory.textNode(v.toString)
    case v:java.util.Map[_,_]=>val n=Json.obj();v.asScala.foreach{case(k,x)=>n.set[com.fasterxml.jackson.databind.JsonNode](k.toString,sampleValue(x))};n
    case v:java.util.List[_]=>val n=Json.mapper.createArrayNode();v.asScala.foreach(x=>n.add(sampleValue(x)));n
    case v:java.time.temporal.TemporalAccessor=>Json.mapper.getNodeFactory.textNode(v.toString)
    case v:Array[Byte]=>Json.mapper.getNodeFactory.textNode(java.util.Base64.getEncoder.encodeToString(v))
    case v=>Json.mapper.valueToTree[com.fasterxml.jackson.databind.JsonNode](v)
  }
  def main(args:Array[String]):Unit={
    if(args.headOption.contains("build-operator")){
      require(args.length==3,"Usage: xstream-runner build-operator CONFIG.yaml OPERATOR_DIRECTORY")
      val config=YamlDocument.parse(Files.readString(Path.of(args(1))))
      Using.resource(new PluginEnvironment(Option(config.get("plugin_directory")).map(n=>Path.of(n.asText)))){plugins=>
        Using.resource(new LocalEngine(config,plugins)){host=>
          _root_.xstream.operator.inference.OperatorInference.withDocker { docker=>
            val op=_root_.xstream.operator.inference.LocalOperatorLibrary.resolve(host,Path.of(".").toAbsolutePath,args(2),docker,()=>())
            val n=_root_.xstream.operator.inference.OperatorLibrary.runtimeDescriptor(op).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
            n.put("artifact",op.artifact.get.toString);println(n.toPrettyString)
          }
        }
      }
      return
    }

    require(args.length>=2 && args.length<=4,"Usage: xstream-runner CONFIG.yaml JOB.yaml [VARIABLES.yaml] [POLICY.yaml]")
    val config=YamlDocument.parse(Files.readString(Path.of(args(0))))
    // Relative operator references are anchored to the Job file, never to launcher cwd.
    require(config.isObject,"runner configuration must be a mapping")
    if(!config.has("job_working_directory"))config.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("job_working_directory",Path.of(args(1)).toAbsolutePath.normalize.getParent.toString)
    val variables=if(args.length>=3)YamlDocument.parse(Files.readString(Path.of(args(2))))else Json.obj()
    val policy=if(args.length>=4)YamlDocument.parse(Files.readString(Path.of(args(3))))else Json.obj()
    val yaml=WorkflowVariables.resolve(Files.readString(Path.of(args(1))),variables)
    require(Jobs.parse(yaml).outputs.size==1,"xstream-runner stdout requires exactly one selected output; embedded JobEngine supports multiple outputs")
    val representations=scala.collection.mutable.Map.empty[String,String]
    Using.resource(new PluginEnvironment(Option(config.get("plugin_directory")).map(n=>Path.of(n.asText)))){plugins=>
      Using.resource(new LocalEngine(config,plugins)){host=>
        Using.resource(new StreamCancel(timeoutSeconds=host.executionTimeoutSeconds)){cancel=>
          host.engine.run(yaml,frame=>{
            val h=StreamProtocol.header(frame)
            val port=h.path("port").asText
            if(h.path("type").asText=="start")representations(port)=h.path("representation").asText
            if(h.path("type").asText=="data"){
              if(representations.get(port).contains("records"))Using.resource(new org.apache.arrow.vector.ipc.ArrowStreamReader(new java.io.ByteArrayInputStream(frame.data),host.allocator)){reader=>
                import scala.jdk.CollectionConverters._
                while(reader.loadNextBatch()){
                  val root=reader.getVectorSchemaRoot
                  (0 until root.getRowCount).foreach {i=>val row=Json.obj();root.getFieldVectors.asScala.foreach(v=>row.set[com.fasterxml.jackson.databind.JsonNode](v.getName,sampleValue(v.getObject(i))));System.out.println(row.toString)}
                }
              }else System.out.write(frame.data)
              System.out.flush()
            }
          },cancel,policy)
        }
      }
    }
  }
}
