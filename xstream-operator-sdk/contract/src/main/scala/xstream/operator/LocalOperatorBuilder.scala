package xstream.operator

import java.nio.file.Path
import com.fasterxml.jackson.databind.JsonNode
import xstream.data.Plugin

/** Trusted local development build; never enabled by remote Job submission. */
trait LocalOperatorBuilder extends Plugin {
  /** Return a regular artifact inside destination. check must be polled during long builds. */
  def build(directory:Path,settings:JsonNode,destination:Path,check:()=>Unit):Path
}
