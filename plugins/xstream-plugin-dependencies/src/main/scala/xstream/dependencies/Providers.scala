package xstream.dependencies

import xstream.operator._
import java.nio.file.{Files,Path,StandardCopyOption}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import com.fasterxml.jackson.databind.JsonNode

/** Tool processes receive argv, fixed scripts and isolated containers, never host source execution. */
private[dependencies] object Tools {
  def run(args:Seq[String],check:()=>Unit,timeout:Long=600):String={
    val output=Files.createTempFile("xstream-dependency-log-",".log")
    val p=new ProcessBuilder(args.asJava).redirectErrorStream(true).redirectOutput(output.toFile).start();p.getOutputStream.close()
    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout)
    try {
      while(!p.waitFor(100,TimeUnit.MILLISECONDS)){check();require(System.nanoTime()<end,"dependency tool timed out")}
      check();val in=Files.newInputStream(output);val text=try new String(in.readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8)finally in.close()
      require(p.exitValue()==0,"dependency tool failed: "+text.take(4000));text.trim
    }finally {if(p.isAlive){val c=p.toHandle.descendants();try c.iterator.asScala.foreach(_.destroyForcibly())finally c.close();p.destroyForcibly()};Files.deleteIfExists(output)}
  }
  def container(r:DependencyRequest,work:Path,command:Seq[String],check:()=>Unit):String={
    require(r.builderImage.matches("sha256:[a-f0-9]{64}"),"dependency builder must be pinned")
    val name="xstream-deps-"+java.util.UUID.randomUUID()
    val user=Files.getAttribute(work,"unix:uid").toString+":"+Files.getAttribute(work,"unix:gid").toString
    try run(Seq("docker","run","--rm","--name",name,"--user",user,"--cap-drop","ALL","--security-opt","no-new-privileges","--pids-limit","128","--memory","1g","--cpus","2","--read-only","--tmpfs","/tmp:rw,nosuid,nodev,size=512m","--mount",s"type=bind,src=${work.toAbsolutePath},dst=/output","--env","COURSIER_CACHE=/output/coursier","--entrypoint",command.head,r.builderImage)++command.tail,check)
    finally try run(Seq("docker","rm","-f",name),()=>(),15)catch{case scala.util.control.NonFatal(_)=>()}
  }
  def safeCopy(source:Path,target:Path):Unit={require(Files.isRegularFile(source) && !Files.isSymbolicLink(source),"dependency material must be a regular file");Files.createDirectories(target.getParent);Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING)}
  def native(r:DependencyRequest,work:Path):Unit=Seq("bin","lib").foreach { name=>
    val from=r.source.resolve(name)
    if(Files.exists(from)){
      require(Files.isDirectory(from) && !Files.isSymbolicLink(from),"native bundle root must be a real directory")
      val walk=Files.walk(from);try walk.iterator.asScala.foreach { p=>
        require(!Files.isSymbolicLink(p),"native bundle cannot contain symbolic links")
        if(Files.isRegularFile(p)){
          val relative=from.relativize(p);require(relative.iterator.asScala.forall(_.toString.matches("[A-Za-z0-9_.+-]+")),"invalid native dependency path")
          val target=work.resolve("payload").resolve(name).resolve(relative);safeCopy(p,target)
          if(name=="bin")Files.setPosixFilePermissions(target,java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"))
        }
      }finally walk.close()
    }
  }
  def lock(id:String,r:DependencyRequest,work:Path,check:()=>Unit):JsonNode={
    val n=DependencyEnvironments.mapper.createObjectNode().put("provider",id).put("version",1)
    val platform=run(Seq("docker","image","inspect","--format","{{.Os}}/{{.Architecture}}",r.runtimeImage),check,30)
    n.put("platform",platform);val files=n.putArray("files");val payload=work.resolve("payload")
    Files.createDirectories(payload)
    val walk=Files.walk(payload);try walk.iterator.asScala.filter(Files.isRegularFile(_)).toVector.sortBy(p=>payload.relativize(p).toString).foreach { p=>
      check();files.addObject().put("path",payload.relativize(p).toString.replace('\\','/')).put("sha256",DependencyEnvironments.checksum(p))
    }finally walk.close()
    n
  }
  def image(r:DependencyRequest,lock:JsonNode,work:Path,check:()=>Unit):(String,Path)={
    // Payload includes installed Python modules/JARs/native libraries only, never operator code.
    val dockerfile=s"FROM ${r.runtimeImage}\nCOPY payload/ /opt/xstream/\nENV PYTHONPATH=/opt/xstream/python:$${PYTHONPATH} PATH=/opt/xstream/bin:$${PATH} LD_LIBRARY_PATH=/opt/xstream/lib:$${LD_LIBRARY_PATH}\n"
    Files.writeString(work.resolve("Dockerfile"),dockerfile)
    val tag="xstream-dependency:"+DependencyEnvironments.fingerprint(r.runtimeImage,lock)
    run(Seq("docker","build","--network","none","--tag",tag,work.toString),check)
    val image=run(Seq("docker","image","inspect","--format","{{.Id}}",tag),check,30)
    val archive=work.resolve("environment.tar");run(Seq("docker","save","--output",archive.toString,image),check)
    image->archive
  }
}
final class PythonDependencies extends DependencyEnvironmentProvider {
  val id="python"
  def resolve(r:DependencyRequest,work:Path,check:()=>Unit):JsonNode={
    require(r.settings.fieldNames.asScala.toSet==Set("provider"),"python dependencies accept only provider; use pyproject.toml")
    Tools.safeCopy(r.source.resolve("pyproject.toml"),work.resolve("pyproject.toml"))
    // uv's resolver and installer run in the administrator-selected builder, not on the host.
    Tools.container(r,work,Seq("/usr/local/bin/python","-c","""import os,subprocess,sys
python=os.path.join(sys.prefix,'bin','python')
if not os.path.isfile(python): python=os.path.realpath(sys.executable)
os.environ.update(UV_CACHE_DIR='/tmp/uv',UV_NO_PROGRESS='1',UV_PYTHON_DOWNLOADS='never')
subprocess.run(['/usr/local/bin/uv','pip','compile','/output/pyproject.toml','--generate-hashes','--no-header','--no-annotate','--python',python,'--output-file','/output/requirements.lock'],check=True)
os.makedirs('/output/payload/python',exist_ok=True)
subprocess.run(['/usr/local/bin/uv','pip','install','--require-hashes','--no-deps','--python',python,'--target','/output/payload/python','-r','/output/requirements.lock'],check=True)
"""),check)
    Tools.native(r,work);val n=Tools.lock(id,r,work,check).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    n.put("requirements",Files.readString(work.resolve("requirements.lock")));n
  }
  def build(r:DependencyRequest,lock:JsonNode,work:Path,check:()=>Unit):(String,Path)=Tools.image(r,lock,work,check)
}
final class JvmDependencies extends DependencyEnvironmentProvider {
  val id="jvm"
  private[dependencies] def coordinates(r:DependencyRequest):Vector[String]={
    require(r.settings.fieldNames.asScala.toSet.subsetOf(Set("provider","coordinates")),"unknown JVM dependency setting")
    val explicit=Option(r.settings.get("coordinates")).orElse {
      val path=r.source.resolve("dependencies.json");if(Files.isRegularFile(path))Some(DependencyEnvironments.mapper.readTree(path.toFile))else None
    }
    val values=explicit.map { n=>require(n.isArray,"JVM coordinates must be an array");n.elements.asScala.map(_.asText).toVector }.getOrElse {
      // Maven declarations with explicit versions. Complex Gradle/sbt/Maven builds export dependencies.json.
      val f=javax.xml.parsers.DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
      f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
      val doc=f.newDocumentBuilder().parse(r.source.resolve("pom.xml").toFile)
      val nodes=doc.getDocumentElement.getChildNodes
      val deps=(0 until nodes.getLength).map(nodes.item).find(_.getNodeName=="dependencies").toVector
      deps.flatMap { parent=>val children=parent.getChildNodes;(0 until children.getLength).map(children.item).filter(_.getNodeName=="dependency").map { d=>
        def text(name:String):String={val c=d.getChildNodes;(0 until c.getLength).map(c.item).find(_.getNodeName==name).map(_.getTextContent.trim).getOrElse("")}
        require(!Set("test","provided","system")(text("scope")),"export runtime dependencies.json for non-runtime Maven scopes")
        text("groupId")+":"+text("artifactId")+":"+text("version")
      }}
    }
    require(values.forall(_.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+")),"use exact Maven coordinates; export resolved versions instead of properties or ranges")
    require(!values.exists(v=>v.startsWith("org.apache.arrow:") || v.startsWith("io.github.rdcn-link:xstream-")),"shared Arrow and XStream APIs belong to the runtime")
    values.distinct.sorted
  }
  def resolve(r:DependencyRequest,work:Path,check:()=>Unit):JsonNode={
    val deps=coordinates(r);Files.writeString(work.resolve("coordinates"),deps.mkString("\n"))
    Files.createDirectories(work.resolve("payload/jars"))
    if(deps.nonEmpty){
      val classpath=Tools.container(r,work,Seq("/usr/local/bin/cs","fetch","--classpath","--")++deps,check).linesIterator.toVector.lastOption.getOrElse("")
      classpath.split(":").filter(_.nonEmpty).foreach { containerPath=>
        require(containerPath.startsWith("/output/coursier/"),"Coursier artifact escaped the writable cache")
        val source=work.resolve(containerPath.stripPrefix("/output/"));require(source.normalize.startsWith(work),"invalid dependency path")
        val target=work.resolve("payload/jars").resolve(source.getFileName)
        if(Files.exists(target))require(DependencyEnvironments.checksum(source)==DependencyEnvironments.checksum(target),"conflicting JAR filenames")else Tools.safeCopy(source,target)
      }
    }
    Tools.native(r,work);val n=Tools.lock(id,r,work,check).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    val a=n.putArray("coordinates");deps.foreach(a.add);n
  }
  def build(r:DependencyRequest,lock:JsonNode,work:Path,check:()=>Unit):(String,Path)=Tools.image(r,lock,work,check)
}
final class NativeDependencies extends DependencyEnvironmentProvider {
  val id="native"
  def resolve(r:DependencyRequest,work:Path,check:()=>Unit):JsonNode={
    require(r.settings.fieldNames.asScala.toSet==Set("provider"),"native dependencies accept only provider; bundle bin/ and lib/")
    Tools.native(r,work);Tools.lock(id,r,work,check)
  }
  def build(r:DependencyRequest,lock:JsonNode,work:Path,check:()=>Unit):(String,Path)=Tools.image(r,lock,work,check)
}
