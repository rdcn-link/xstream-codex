package xstream.operator.executors

import java.nio.file.{Files,Path,StandardCopyOption}
import java.util.concurrent.TimeUnit
import java.util.zip.{ZipEntry,ZipOutputStream}
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._
import xstream.operator.LocalOperatorBuilder

/** The same src/ tree is submitted for repository builds. No host Python dependency needed. */
final class PythonLocalBuilder extends LocalOperatorBuilder {
  val id="python"
  def build(directory:Path,settings:JsonNode,destination:Path,check:()=>Unit):Path={
    require(settings.isObject && !settings.fieldNames.asScala.exists(_!="provider"),"python local build accepts only provider")
    val src=directory.resolve("src").toRealPath();require(src.startsWith(directory.toRealPath()),"source directory escapes operator directory")
    val files=Files.walk(src)
    val paths=try files.iterator.asScala.filter(p=>Files.isRegularFile(p) && !src.relativize(p).iterator.asScala.exists(_.toString=="__pycache__") && !p.toString.endsWith(".pyc")).toVector.sortBy(_.toString)finally files.close()
    require(paths.exists(_.toString.endsWith(".py")),"src/ must contain Python sources")
    val target=destination.resolve("operator.zip");val out=new ZipOutputStream(Files.newOutputStream(target))
    try {paths.foreach { p=>
      check();require(p.toRealPath().startsWith(src),"source link escapes operator directory")
      val e=new ZipEntry(src.relativize(p).toString.replace('\\','/'));e.setTime(0);out.putNextEntry(e)
      Files.copy(p,out);out.closeEntry()
    }
      val modules=paths.filter(p=>p.toString.endsWith(".py") && "(?m)^def process\\(ctx\\):".r.findFirstIn(Files.readString(p)).nonEmpty).map(p=>src.relativize(p).toString.stripSuffix(".py").replace('/','.'))
      val metadata=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();val values=metadata.putArray("python_process_modules");modules.foreach(values.add)
      out.putNextEntry(new ZipEntry(".dacp-build.json"));out.write(metadata.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry()
    }finally out.close()
    target
  }
}

/** Build-tool adapter for JVM/native/custom languages; command is an argv array, never a shell string. */
final class CommandLocalBuilder extends LocalOperatorBuilder {
  val id="command"
  def build(directory:Path,settings:JsonNode,destination:Path,check:()=>Unit):Path={
    require(settings.isObject && settings.fieldNames.asScala.toSet.subsetOf(Set("provider","command","artifact")),"unknown command build setting")
    val args=settings.path("command");require(args.isArray && args.size>0 && args.elements.asScala.forall(v=>v.isTextual && v.asText.nonEmpty),"build.command must be a nonempty argv array")
    val artifact=settings.path("artifact").asText
    require(artifact.nonEmpty && !Path.of(artifact).isAbsolute,"build.artifact must be a relative path")
    require(directory.resolve(artifact).normalize.startsWith(directory),"build artifact escapes operator directory")
    check()
    val process=new ProcessBuilder(args.elements.asScala.map(_.asText).toVector.asJava).directory(directory.toFile).redirectErrorStream(true).start()
    process.getOutputStream.close()
    val reader=new Thread(()=>{
      val in=process.getInputStream;val buffer=new Array[Byte](4096);var remaining=65536
      try {var n=in.read(buffer);while(n != -1){if(remaining>0){val count=math.min(n,remaining);System.err.write(buffer,0,count);remaining-=count};n=in.read(buffer)}}catch{case _:java.io.IOException=>()}finally in.close()
    },"xstream-local-build-log")
    reader.setDaemon(true);reader.start()
    try {
      while(!process.waitFor(100,TimeUnit.MILLISECONDS))check()
      check();reader.join(1000);require(process.exitValue()==0,s"local build exited with ${process.exitValue()}")
      val source=directory.resolve(artifact).toRealPath()
      require(source.startsWith(directory.toRealPath()) && Files.isRegularFile(source),"built artifact must be inside operator directory")
      val target=destination.resolve(source.getFileName);Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);target
    }finally {
      val descendants=process.toHandle.descendants();try descendants.iterator.asScala.toVector.reverse.foreach(_.destroyForcibly())finally descendants.close()
      if(process.isAlive)process.destroyForcibly()
      process.getInputStream.close()
    }
  }
}

/** Local Java compilation in the selected runtime, including minimal images without a shell. */
final class JvmLocalBuilder extends LocalOperatorBuilder {
  val id="jvm"
  def build(directory:Path,settings:JsonNode,destination:Path,check:()=>Unit):Path={
    require(settings.fieldNames.asScala.toSet==Set("provider","image"),"JVM local build requires provider and image")
    val image=settings.path("image").asText;require(image.nonEmpty && !image.startsWith("-"),"invalid JVM build image")
    val src=directory.resolve("src").toRealPath();val walk=Files.walk(src)
    val sources=try walk.iterator.asScala.filter(p=>Files.isRegularFile(p) && p.toString.endsWith(".java")).toVector.sortBy(_.toString)finally walk.close()
    require(sources.nonEmpty && sources.forall(_.toRealPath().startsWith(src)),"JVM local builder needs src/*.java sources")
    val name="xstream-javac-"+java.util.UUID.randomUUID()
    val user=Files.getAttribute(destination,"unix:uid").toString+":"+Files.getAttribute(destination,"unix:gid").toString
    val args=Seq("docker","run","--rm","--name",name,"--network","none","--user",user,"--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--tmpfs","/tmp:rw,nosuid,nodev,size=128m","--mount",s"type=bind,src=$src,dst=/source,readonly","--mount",s"type=bind,src=$destination,dst=/output","--entrypoint","/opt/java/bin/javac",image,"-proc:none","-cp","/opt/lib/*:/opt/xstream/jars/*","-d","/output")++sources.map(p=>"/source/"+src.relativize(p).toString)
    val build= new ProcessBuilder(args.asJava).inheritIO().start()
    try {while(!build.waitFor(100,TimeUnit.MILLISECONDS))check();check();require(build.exitValue()==0,"Java operator compilation failed")}
    finally {if(build.isAlive)build.destroyForcibly();val cleanup=new ProcessBuilder("docker","rm","-f",name).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();cleanup.waitFor(15,TimeUnit.SECONDS)}
    val target=destination.resolve("operator.jar");val out=new java.util.jar.JarOutputStream(Files.newOutputStream(target))
    val files=Files.walk(destination)
    try files.iterator.asScala.filter(p=>Files.isRegularFile(p) && p.toString.endsWith(".class")).toVector.sortBy(_.toString).foreach { p=>
      out.putNextEntry(new java.util.jar.JarEntry(destination.relativize(p).toString));Files.copy(p,out);out.closeEntry()
    }finally {files.close();out.close()}
    target
  }
}
