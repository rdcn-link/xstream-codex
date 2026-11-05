package benchmarks

import java.nio.file.{Files,Path}
import java.security.MessageDigest
import xstream.engine.LocalEngine
import xstream.execution.{StreamCancel,StreamProtocol}
import xstream.data.PluginEnvironment
import xstream.util.{Json,YamlDocument}

/** Persistent host: framework startup is recorded separately, never charged only to XStream. */
object Host {
  def main(args:Array[String]):Unit={
    val output=System.out
    val settings=YamlDocument.parse(Files.readString(Path.of(args(0))))
    val plugins=new PluginEnvironment(None)
    val host=new LocalEngine(settings,plugins)
    def reply(n:com.fasterxml.jackson.databind.JsonNode):Unit={output.println(n.toString);output.flush()}
    reply(Json.obj().put("ready",true))
    try scala.io.Source.stdin.getLines().foreach { line=>
      val request=Json.mapper.readTree(line)
      val result=Json.obj()
      val digest=MessageDigest.getInstance("SHA-256")
      var bytes=0L;var lines=0L;var first=0L
      val started=System.nanoTime()
      val cancel=new StreamCancel(timeoutSeconds=1800)
      try {
        val policy=YamlDocument.parse("materialization:\n  allowed: false\n  default: stream\n")
        val id=host.engine.run(Files.readString(Path.of(request.path("job").asText)),frame=>{
          val h=StreamProtocol.header(frame)
          if(h.path("type").asText=="start")require(h.path("representation").asText=="bytes","benchmark requires CSV bytes output")
          if(h.path("type").asText=="data" && frame.data.nonEmpty){
            if(first==0)first=System.nanoTime()
            digest.update(frame.data);bytes+=frame.data.length;lines+=frame.data.count(_==10.toByte)
          }
        },cancel,policy)
        result.put("state","succeeded").put("execution_id",id).put("bytes",bytes).put("rows",math.max(0L,lines-1))
        host.executionHistory.get(id).foreach { record=>
          result.set[com.fasterxml.jackson.databind.JsonNode]("execution_units",record.path("execution_units"))
          result.set[com.fasterxml.jackson.databind.JsonNode]("materialized_sources",record.path("materialized_sources"))
        }
        result.put("sha256",digest.digest().map(b=>f"${b&255}%02x").mkString)
        if(first!=0)result.put("first_data_s",(first-started)/1e9)
      }catch {case scala.util.control.NonFatal(e)=>result.put("state","failed").put("error",Option(e.getMessage).getOrElse(e.getClass.getName));e.printStackTrace(System.err)}
      finally cancel.close()
      result.put("wall_s",(System.nanoTime()-started)/1e9)
      reply(result)
    }finally {host.close();plugins.close()}
  }
}
