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
    try paths.foreach { p=>
      check();require(p.toRealPath().startsWith(src),"source link escapes operator directory")
      val e=new ZipEntry(src.relativize(p).toString.replace('\\','/'));e.setTime(0);out.putNextEntry(e)
      Files.copy(p,out);out.closeEntry()
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
