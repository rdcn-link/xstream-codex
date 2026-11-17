package xstream.plugins.analysis


import xstream.operator.SourceBuildRecipe
import java.nio.file.Path

private object ArtifactEntrypoint {
  def check(path:Path,entries:Seq[String]):Unit={val zip=new java.util.zip.ZipFile(path.toFile);try require(entries.exists(e=>zip.getEntry(e)!=null),"entrypoint is missing from the built artifact")finally zip.close()}
}

final class PythonBuildRecipe extends SourceBuildRecipe {
  def language="python"
  def executor="python"
  def artifactFilename="operator.zip"
  def verifyArtifact(path:Path,entrypoint:String):Unit=ArtifactEntrypoint.check(path,Seq(entrypoint.replace('.','/')+".pyc",entrypoint.replace('.','/')+"/__main__.pyc"))
  def command:Seq[String]={
    val in=getClass.getResourceAsStream("/xstream-python-build.py")
    require(in!=null,"Python build resource unavailable")
    val script=try new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)finally in.close()
    Seq("/usr/local/bin/python","-c",script)
  }
}
final class JavaBuildRecipe extends SourceBuildRecipe {
  def language="java"
  def executor="jvm"
  def artifactFilename="operator.jar"
  def verifyArtifact(path:Path,entrypoint:String):Unit=ArtifactEntrypoint.check(path,Seq(entrypoint.replace('.','/')+".class"))
  def command=Seq("/opt/java/bin/java","-cp","/builder/helper.jar:/opt/lib/*","xstream.repository.build.JvmCompiler","java")
}
final class ScalaBuildRecipe extends SourceBuildRecipe {
  def language="scala"
  def executor="jvm"
  def artifactFilename="operator.jar"
  def verifyArtifact(path:Path,entrypoint:String):Unit=ArtifactEntrypoint.check(path,Seq(entrypoint.replace('.','/')+".class"))
  def command=Seq("/opt/java/bin/java","-cp","/builder/helper.jar:/opt/lib/*","xstream.repository.build.JvmCompiler","scala")
}

/** Copy and validate the declarative transport program in the isolated builder. */
final class StreamBuildRecipe extends SourceBuildRecipe {
  def language="stream"
  def executor="stream"
  def artifactFilename="endpoint.json"
  def command=Seq("/opt/java/bin/java","-cp","/builder/helper.jar","xstream.repository.build.JvmCompiler","copy","endpoint.json")
  def verifyArtifact(path:Path,entrypoint:String):Unit={xstream.operator.StreamEndpoint.read(path,entrypoint);()}
}
