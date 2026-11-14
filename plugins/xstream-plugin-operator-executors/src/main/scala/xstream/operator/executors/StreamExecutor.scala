package xstream.operator.executors

import xstream.execution.{OperatorCommand,OperatorExecutor,OperatorProgram}
import xstream.operator.StreamEndpoint

/** Verified declarative programs select transport services; they cannot execute host code. */
final class StreamExecutor extends OperatorExecutor {
  val id="stream"
  def command(program:OperatorProgram,artifactPath:Option[String]):OperatorCommand={
    require(program.configuration.isEmpty,"stream executor does not accept configuration")
    val operation=StreamEndpoint.read(program.artifact.getOrElse(throw new IllegalArgumentException("stream endpoint program required")),program.entrypoint)
    OperatorCommand(arguments=Vector.empty,environment=Map("XSTREAM_STREAM_ENDPOINT"->operation))
  }
}
