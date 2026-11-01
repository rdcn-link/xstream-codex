package xstream.designer

import com.fasterxml.jackson.databind.JsonNode
import xstream.util.{Json,YamlDocument,WorkflowVariables}
import scala.jdk.CollectionConverters._

/** Packaged authoring examples; loading an example never executes a Job. */
private[designer] object ExampleJobs {
  private def read(name:String,directory:Option[java.nio.file.Path]):String={
    require(name.matches("[a-z][a-z0-9-]*\\.yaml"),"invalid example resource name")
    val in=directory.map(d=>java.nio.file.Files.newInputStream(d.resolve(name))).getOrElse(getClass.getResourceAsStream("/examples/"+name))
    require(in!=null,"example resource unavailable")
    val bytes=try in.readNBytes(256*1024+1)finally in.close()
    require(bytes.length<=256*1024,"example resource exceeds 256 KiB")
    new String(bytes,java.nio.charset.StandardCharsets.UTF_8)
  }
  lazy val all:JsonNode=load(None)
  def load(directory:Option[java.nio.file.Path]):JsonNode={
    val index=YamlDocument.parse(read("index.yaml",directory));require(index.isArray,"invalid example index")
    val result=Json.mapper.createArrayNode()
    index.elements.asScala.foreach { entry=>
      Json.fields(entry,Set("id","title","description","job","variables"),Set("tags"))
      val script=read(entry.path("job").asText,directory);val variables=read(entry.path("variables").asText,directory)
      val document=Documents.job(script);val values=Documents.variables(variables)
      Documents.job(WorkflowVariables.resolve(script,values))
      val example=result.addObject().put("id",entry.path("id").asText).put("title",entry.path("title").asText)
        .put("description",entry.path("description").asText).put("job_yaml",script).put("variables_yaml",variables).put("step_count",document.path("steps").size)
      val tags=example.putArray("tags")
      Option(entry.get("tags")).foreach { values=>
        require(values.isArray && values.size<=8,"example tags must be an array of at most eight labels")
        val labels=values.elements.asScala.toVector
        require(labels.forall(t=>t.isTextual && t.asText.trim.nonEmpty && t.asText.length<=40 && !t.asText.exists(_.isControl)),"invalid example tag")
        labels.map(_.asText.trim).distinct.foreach(tags.add)
      }
      val requires=example.putArray("requires")
      document.path("steps").elements.asScala.map(_.path("operator").asText).filterNot(_=="read_item@1.0").toVector.distinct.sorted.foreach(requires.add)
    }
    require(result.elements.asScala.map(_.path("id").asText).toVector.distinct.size==result.size,"duplicate example ID")
    result
  }
}
