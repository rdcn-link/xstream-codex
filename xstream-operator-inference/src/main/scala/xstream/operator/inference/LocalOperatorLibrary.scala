package xstream.operator.inference

import _root_.xstream.execution.{OperatorPorts}

import java.nio.file.{Files,Path,FileVisitResult,SimpleFileVisitor,StandardCopyOption}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import com.fasterxml.jackson.databind.JsonNode
import com.github.dockerjava.api.DockerClient
import scala.jdk.CollectionConverters._
import xstream.data.Data
import _root_.xstream.util.{Json,YamlDocument}
import xstream.operator.LocalOperatorBuilder

case class PreparedLocalOperator(reference:String,definition:JsonNode,image:String,executor:String,artifact:Path)

/** Explicit local references only. Remote operator lookup never interprets source-directory manifests. */
object LocalOperatorLibrary {
  private val ignored=Set(".git","target","build","dist","__pycache__",".venv",".xstream-operators",".xstream-executions")
  private def checksum(path:Path,check:()=>Unit):String={
    val md=MessageDigest.getInstance("SHA-256");val in=Files.newInputStream(path)
    try {val bytes=new Array[Byte](65536);var n=in.read(bytes);while(n != -1){check();md.update(bytes,0,n);n=in.read(bytes)}}finally in.close()
    md.digest().map(b=>f"${b&255}%02x").mkString
  }
  private def document(path:Path):JsonNode={require(Files.size(path)<=65536,"local operator declaration exceeds 64 KiB");val n=YamlDocument.parse(Files.readString(path));require(n.isObject,"local operator declaration must be a mapping");n}
  private def files(directory:Path,artifact:Option[String]):Vector[Path]={
    val out=Vector.newBuilder[Path];var bytes=0L;var count=0
    Files.walkFileTree(directory,new SimpleFileVisitor[Path]{
      override def preVisitDirectory(p:Path,a:BasicFileAttributes):FileVisitResult=if(p!=directory && ignored(p.getFileName.toString))FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
      override def visitFile(p:Path,a:BasicFileAttributes):FileVisitResult={
        require(!a.isSymbolicLink,"local source trees cannot contain symbolic links")
        if(a.isRegularFile && !artifact.contains(directory.relativize(p).toString)){
          count+=1;bytes+=a.size;require(count<=16384 && bytes<=64L*1024*1024,"local sources exceed 16384 files or 64 MiB")
          out+=p
        };FileVisitResult.CONTINUE
      }
    });out.result().sortBy(_.toString)
  }
  private def fingerprint(directory:Path,artifact:Option[String],check:()=>Unit):String={
    val md=MessageDigest.getInstance("SHA-256")
    md.update(directory.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    files(directory,artifact).foreach { p=>
      check();val name=directory.relativize(p).toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      md.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());md.update(name)
      md.update(java.nio.ByteBuffer.allocate(8).putLong(Files.size(p)).array())
      val in=Files.newInputStream(p);try{val buffer=new Array[Byte](65536);var n=in.read(buffer);while(n != -1){check();md.update(buffer,0,n);n=in.read(buffer)}}finally in.close()
    };md.digest().map(b=>f"${b&255}%02x").mkString
  }
  def prepare(config:OperatorEnvironment,base:Path,reference:String,check:()=>Unit):PreparedLocalOperator=synchronized {
    check();require(reference.nonEmpty,"local operator reference must not be empty")
    val versioned=reference.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*@[A-Za-z0-9_-][A-Za-z0-9_.-]*")
    val path=if(versioned){val p=reference.split("@",-1);config.localOperatorLibrary.resolve(p(0)).resolve(p(1))}else base.resolve(reference)
    val directory=path.toRealPath();require(Files.isDirectory(directory),"local operator must be a directory")
    val definition=document(directory.resolve("operator.yaml"));val local=document(directory.resolve("local.yaml"))
    require(definition.fieldNames.asScala.toSet.subsetOf(Set("name","version","description","icon","inputs","parameters","language","entrypoint","runtime","source_public","provenance","dependencies","submission","binary","execution_config")),"unknown source operator field")
    val name=definition.path("name").asText;val version=definition.path("version").asText
    require(Seq(name,version).forall(_.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*")),"local operator requires name and version")
    require(!versioned || reference==s"$name@$version","local operator identity mismatch")
    require(definition.path("language").isTextual && definition.path("runtime").asText.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*@[A-Za-z0-9_-][A-Za-z0-9_.-]*"),"local operator requires language and versioned runtime")
    require(definition.path("entrypoint").asText.nonEmpty,"local operator entrypoint required")
    xstream.operator.OperatorPresentation.validate(definition);xstream.operator.ParameterSchema.validate(definition.get("parameters"))
    xstream.execution.OperatorPorts.read(definition.path("inputs"))
    require(local.fieldNames.asScala.toSet.subsetOf(Set("image","executor","build","dependency_builder_image")),"unknown local runtime setting")
    require(local.path("image").isTextual && local.path("image").asText.nonEmpty && local.path("executor").isTextual,"local image and executor are required")
    val build=local.path("build");require(build.isObject,"local build must be a mapping")
    val builder=config.plugins.byId(classOf[LocalOperatorBuilder],build.path("provider").asText)
    val excluded=Option(build.get("artifact")).map(_.asText)
    val key=fingerprint(directory,excluded,check)
    val parent=config.operatorCache.toAbsolutePath.resolve("local-builds");Files.createDirectories(parent)
    val cache=parent.resolve(key)
    val result=cache.resolve("artifact.json")
    def existing():Option[Path]=if(Files.isRegularFile(result)){
      val metadata=Data.mapper.readTree(result.toFile);val filename=metadata.path("filename").asText
      require(filename.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*"),"invalid cached artifact filename")
      val artifact=cache.resolve(filename)
      if(Files.isRegularFile(artifact) && Files.size(artifact)>0 && Files.size(artifact)<=config.operatorMaxBytes && checksum(artifact,check)==metadata.path("sha256").asText)Some(artifact)else None
    }else None
    val artifact=Json.locked(parent.resolve(key+".lock")){existing().getOrElse {
      val stage=Files.createTempDirectory(parent,"build-")
      try {
        val output=builder.build(directory,build,stage,check).toAbsolutePath.normalize
        require(output.startsWith(stage) && Files.isRegularFile(output) && !Files.isSymbolicLink(output),"local builder must return a regular artifact in its destination")
        require(output.getFileName.toString.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*") && Files.size(output)>0 && Files.size(output)<=config.operatorMaxBytes && output.getFileName.toString!="artifact.json","invalid local artifact name or size")
        require(fingerprint(directory,excluded,check)==key,"operator sources changed during build; retry")
        check();Files.write(stage.resolve("artifact.json"),Data.mapper.writeValueAsBytes(Data.mapper.createObjectNode().put("filename",output.getFileName.toString).put("sha256",checksum(output,check))))
        if(Files.exists(cache))Data.deleteTree(cache)
        Files.move(stage,cache,StandardCopyOption.ATOMIC_MOVE)
        cache.resolve(output.getFileName)
      }finally if(Files.exists(stage))Data.deleteTree(stage)
    }
    }
    PreparedLocalOperator(s"$name@$version",definition,local.path("image").asText,local.path("executor").asText,artifact)
  }
  def resolve(config:OperatorEnvironment,base:Path,reference:String,docker:DockerClient,check:()=>Unit):ResolvedOperator={
    val prepared=prepare(config,base,reference,check);check()
    val image=docker.inspectImageCmd(prepared.image).exec().getId
    val settings=prepared.definition.path("dependencies")
    var environment=Option.empty[JsonNode]
    var selected=image
    if(!settings.isMissingNode){
      val providers=config.plugins.services(classOf[xstream.operator.DependencyEnvironmentProvider])
      val provider=providers.find(_.id==settings.path("provider").asText).getOrElse(throw new IllegalArgumentException("dependency environment provider unavailable"))
      val directory=(if(reference.contains("@") && !reference.contains("/")){val p=reference.split("@",-1);config.localOperatorLibrary.resolve(p(0)).resolve(p(1))}else base.resolve(reference)).toRealPath()
      val cache=new xstream.operator.DependencyEnvironmentCache(config.operatorCache.resolve("dependency-environments"));cache.cleanup()
      val lease=cache.acquire(provider,xstream.operator.DependencyRequest(directory,image,docker.inspectImageCmd(document(directory.resolve("local.yaml")).path("dependency_builder_image").asText(prepared.image)).exec().getId,settings),check)
      try {
        val e=lease.value;selected=e.image
        try docker.inspectImageCmd(selected).exec()catch{case _:com.github.dockerjava.api.exception.NotFoundException=>val in=Files.newInputStream(e.archive);try docker.loadImageCmd(in).exec()finally in.close()}
        val node=Data.mapper.createObjectNode().put("fingerprint",e.fingerprint).put("image",e.image);node.set[JsonNode]("lock",e.lock);environment=Some(node)
      }finally lease.close()
    }
    val configuration=Option(prepared.definition.get("execution_config")).getOrElse(Data.mapper.createObjectNode())
    OperatorLibrary.plan(config,prepared.reference,selected,prepared.executor,prepared.definition.path("entrypoint").asText,
      Some(prepared.artifact),Some(prepared.artifact.getFileName.toString),prepared.definition,configuration).copy(dependencyEnvironment=environment)
  }
}
