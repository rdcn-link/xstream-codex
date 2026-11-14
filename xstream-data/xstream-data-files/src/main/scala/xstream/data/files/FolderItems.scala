package xstream.data.files

import xstream.data._
import org.apache.arrow.vector.VectorSchemaRoot

/** Enumerates through the provider's namespace, without opening member contents. */
private[files] object FolderItems {
  def consume(access:DatasetAccess,path:String,selection:ItemsSelection,context:StreamContext,out:ArrowConsumer,plugins:PluginEnvironment):Unit={
    require(selection.mode=="auto" || selection.mode=="folder","items source kind does not match selected resource")
    require(selection.uriColumn=="uri","uri_column only applies to CSV manifests")
    val root=VectorSchemaRoot.create(ItemReferences.schema,context.allocator)
    try {
      context.check();out.start(ItemReferences.schema,null)
      def visit(folder:String,depth:Int):Unit={
        require(depth<=128,"items directory nesting exceeds 128")
        access.list(folder).foreach { child=>
          context.check();root.clear();root.allocateNew()
          ItemReferences.write(root,0,ItemReferences.fromItem(child,plugins.views(child)))
          root.setRowCount(1);out.accept(ArrowBatch(root))
          if(selection.recursive && child.kind=="folder")visit(child.path,depth+1)
        }
      }
      visit(path,0)
    }finally root.close()
  }
}
