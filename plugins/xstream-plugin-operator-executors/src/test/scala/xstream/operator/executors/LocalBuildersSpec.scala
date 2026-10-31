package xstream.operator.executors

import java.nio.file.{Files,Path}
import java.util.zip.ZipFile
import org.scalatest.funsuite.AnyFunSuite
import xstream.data.Data

class LocalBuildersSpec extends AnyFunSuite {
  test("Python builder packages a source tree with helper modules and no build configuration") {
    val dir=Files.createTempDirectory("local-python-")
    try {
      val src=Files.createDirectories(dir.resolve("src/helper"));Files.writeString(src.resolve("value.py"),"value = 1\n")
      Files.writeString(dir.resolve("local.yaml"),"private local settings")
      val out=Files.createDirectory(dir.resolve("out"))
      val file=new PythonLocalBuilder().build(dir,Data.mapper.readTree("{\"provider\":\"python\"}"),out,()=>())
      val zip=new ZipFile(file.toFile)
      try {assert(zip.getEntry("helper/value.py")!=null);assert(zip.getEntry("local.yaml")==null)}finally zip.close()
      Files.createSymbolicLink(src.resolve("escape.py"),dir.resolve("local.yaml"))
      intercept[IllegalArgumentException](new PythonLocalBuilder().build(dir,Data.mapper.readTree("{}"),out,()=>()))
    }finally Data.deleteTree(dir)
  }
  test("command builder preserves argv and rejects failed builds, escapes and cancellation") {
    val dir=Files.createTempDirectory("local-command-")
    try {
      val out=Files.createDirectory(dir.resolve("out"))
      val settings=Data.mapper.readTree("""{"provider":"command","command":["python3","-c","from pathlib import Path; Path('artifact.jar').write_bytes(b'jar')"],"artifact":"artifact.jar"}""")
      assert(Files.readString(new CommandLocalBuilder().build(dir,settings,out,()=>()))=="jar")
      val failed=settings.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();failed.putArray("command").add("false")
      intercept[IllegalArgumentException](new CommandLocalBuilder().build(dir,failed,out,()=>()))
      val escaped=settings.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();escaped.put("artifact","../outside.jar")
      intercept[IllegalArgumentException](new CommandLocalBuilder().build(dir,escaped,out,()=>()))
      val sleeping=settings.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();sleeping.putArray("command").add("sleep").add("30")
      var checks=0;val start=System.nanoTime()
      intercept[java.util.concurrent.CancellationException](new CommandLocalBuilder().build(dir,sleeping,out,()=>{checks+=1;if(checks>1)throw new java.util.concurrent.CancellationException()}))
      assert((System.nanoTime()-start)/1e9<5)
    }finally Data.deleteTree(dir)
  }
}
