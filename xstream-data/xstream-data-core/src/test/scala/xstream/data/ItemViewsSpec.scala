package xstream.data

import org.scalatest.funsuite.AnyFunSuite

class ItemViewsSpec extends AnyFunSuite {
  test("view selection precedes readable-kind validation") {
    assert(ItemViews.selectForRead("auto","data",Seq("bytes","records"))=="records")
    assert(ItemViews.selectForRead("bytes","data",Seq("bytes"))=="bytes")
    assert(ItemViews.selectForRead("bytes","folder",Seq("bytes"))=="bytes")
    assert(ItemViews.selectForRead("auto","folder",Seq("bytes"))=="bytes")
    intercept[IllegalArgumentException](ItemViews.selectForRead("bytes","folder",Seq.empty))
    intercept[IllegalArgumentException](ItemViews.selectForRead("records","folder",Seq("bytes")))
    intercept[IllegalArgumentException](ItemViews.selectForRead("records","folder",Seq("records","bytes")))
    intercept[IllegalArgumentException](ItemViews.selectForRead("array","folder",Seq("array")))
    intercept[IllegalArgumentException](ItemViews.selectForRead("bytes","unknown",Seq("bytes")))
  }
  test("folders need explicitly advertised bytes, not format-derived capabilities") {
    val plugins=new PluginEnvironment()
    try {
      val root=Item("/input.zip","zip",None,kind="folder",views=Some(Seq("bytes")))
      assert(ItemViews.selectForRead("bytes",root,plugins)=="bytes")
      intercept[IllegalArgumentException](ItemViews.selectForRead("bytes",root.copy(views=None),plugins))
    } finally plugins.close()
  }
}
