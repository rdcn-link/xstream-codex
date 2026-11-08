package xstream.data

/** Shared metadata policy for source inference, execution and authoring. */
object ItemViews {
  /** A navigable container may also expose its original bytes. Other folder views are not readable. */
  def selectForRead(requested:String,kind:String,available:Iterable[String]):String={
    val view=select(requested,available)
    require(kind=="data" || (kind=="folder" && view=="bytes" && available.exists(_=="bytes")),
      "input must be a data item or a folder with an explicit bytes view")
    view
  }
  def selectForRead(requested:String,item:Item,plugins:PluginEnvironment):String=
    selectForRead(requested,item.kind,if(item.kind=="folder")item.views.getOrElse(Seq.empty)else plugins.views(item))

  def select(requested:String,available:Iterable[String]):String={
    val views=available.toSet
    val view=if(requested=="auto"){
      if(views("records"))"records"else if(views("bytes"))"bytes"else available.headOption.getOrElse(throw new IllegalArgumentException("Item has no readable view"))
    }else requested
    require(views(view),"Item does not support requested view: "+view)
    view
  }
}
