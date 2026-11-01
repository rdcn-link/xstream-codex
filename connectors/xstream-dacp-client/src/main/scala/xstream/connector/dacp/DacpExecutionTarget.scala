package xstream.connector.dacp

import xstream.execution._
import xstream.connector.dacp.client._
import xstream.data._
import xstream.util.Json
import _root_.xstream.execution.{OperatorFrame,StreamCancel}
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

final class DacpExecutionTargetProvider extends ExecutionTargetProvider {
  val id="dacp"
  def create(name:String,settings:JsonNode):ExecutionTarget=new DacpExecutionTarget(name,settings)
}
final class DacpExecutionTarget(val id:String,settings:JsonNode) extends ExecutionTarget {
  Json.fields(settings,Set("url"),Set("ca","token_file","startup_millis","transfer_bytes_per_second","estimated_input_bytes","estimated_output_bytes"))
  require(id.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"),"invalid execution target name")
  Seq("startup_millis","transfer_bytes_per_second","estimated_input_bytes","estimated_output_bytes").foreach { key=>
    require(!settings.has(key) || settings.path(key).isIntegralNumber && settings.path(key).canConvertToLong && settings.path(key).asLong>=0,"invalid numeric target setting: "+key)
  }
  require(!settings.has("transfer_bytes_per_second") || settings.path("transfer_bytes_per_second").asLong>0,"transfer rate must be positive")
  private val endpoint=java.net.URI.create(settings.path("url").asText)
  require(endpoint.getScheme=="dacp" && endpoint.getHost!=null && endpoint.getUserInfo==null && endpoint.getQuery==null && endpoint.getFragment==null && Set("","/")(Option(endpoint.getPath).getOrElse("")),"expected DACP origin")
  private val origin=settings.path("url").asText.stripSuffix("/")
  private val tls=TlsConfig(ca=Option(settings.get("ca")).map(n=>Path.of(n.asText)),tokenFile=Option(settings.get("token_file")).map(n=>Path.of(n.asText)))
  def describe(cancel:StreamCancel):TargetDescription={
    cancel.active()
    val n=Source.capabilities(origin,tls,math.min(30L,cancel.remainingSeconds))
    TargetDescription(id,n.path("service").path("job_execute").asBoolean && n.path("permissions").path("job_submit").asBoolean,
      startupMillis=settings.path("startup_millis").asLong(100),transferBytesPerSecond=settings.path("transfer_bytes_per_second").asLong(100000000),
      estimatedInputBytes=Option(settings.get("estimated_input_bytes")).map(_.asLong),estimatedOutputBytes=Option(settings.get("estimated_output_bytes")).map(_.asLong))
  }
  def localize(value:String):Option[String]={
    val u=java.net.URI.create(value)
    if(u.getScheme==endpoint.getScheme && u.getHost==endpoint.getHost && u.getPort==endpoint.getPort)
      Some(new java.net.URI("context",null,u.getPath,null).toASCIIString) else None
  }
  def submit(request:ExecutionRequest,token:StreamCancel):ExecutionHandle={
    require(describe(token).canSubmit,"remote identity cannot submit a Job")
    val stream=token.add(Job.run(origin,request.jobYaml,tls,math.min(request.timeoutSeconds,token.remainingSeconds),request.policy))
    val handle=new ExecutionHandle {
      @volatile private var state="running"
      @volatile private var message:Option[String]=None
      def status():ExecutionStatus=ExecutionStatus(state,stream.executionId,message)
      def next():Option[OperatorFrame]={
        token.active()
        if(state!="running")return None
        try {
          while(stream.hasNext){
            val event=stream.next()
            event.kind match {
              case "execution"=>()
              case "complete"=>token.active();state="succeeded";return None
              case "start"|"data"|"end"=>return Some(OperatorFrame(event.metadata,event.data))
              case _=>throw new IllegalArgumentException("unsupported remote execution event")
            }
          }
          throw new IllegalStateException("remote result stream truncated")
        }catch{case scala.util.control.NonFatal(t)=>state=token.cause(t) match {case _:java.util.concurrent.TimeoutException=>"expired";case _:java.util.concurrent.CancellationException=>"cancelled";case _=>if(state=="cancelled")state else "failed"};message=Option(t.getMessage);throw t}
      }
      def cancel():Unit={if(state=="running")state="cancelled";stream.close()}
      def close():Unit=if(state=="running")cancel()else stream.close()
    }
    token.add(handle)
  }
}
