package xstream.connector.dacp.wire

/** Validate and canonicalize configured remote data-service origins. */
object RemoteOrigin {
  def canonical(s:String):String={
    val u=java.net.URI.create(s)
    require(u.getScheme=="dacp" && u.getHost!=null && u.getUserInfo==null && u.getQuery==null && u.getFragment==null &&
      Set("","/")(Option(u.getPath).getOrElse("")) && (u.getPort == -1 || u.getPort>0 && u.getPort<=65535),"expected dacp://host:port Flight origin")
    val host=u.getHost.toLowerCase(java.util.Locale.ROOT)
    val authority=if(host.contains(":") && !host.startsWith("["))s"[$host]"else host
    s"dacp://$authority:${if(u.getPort == -1)8815 else u.getPort}"
  }
}
