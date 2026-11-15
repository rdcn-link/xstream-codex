package xstream.execution

/** Application-side client_sink receiver. Data is provisional until onComplete. */
trait ResultSink extends AutoCloseable {
  def accept(frame:OperatorFrame):Unit
  def onDiscard(cause:Throwable):Unit
  def onComplete():Unit
}
