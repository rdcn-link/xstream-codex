package xstream.designer

import org.scalatest.funsuite.AnyFunSuite
import _root_.xstream.util.Json
import com.fasterxml.jackson.databind.JsonNode
import java.util.concurrent.Executors

class ChatCatalogContextSpec extends AnyFunSuite {
  test("automatically browses folders and preserves context and remote Item URIs"){
    val pool=Executors.newFixedThreadPool(2)
    val roots=Vector(Json.obj().put("uri","context:/"),Json.obj().put("uri","dacp://node:123/"))
    def load(path:String,n:JsonNode):JsonNode={
      val out=Json.obj()
      if(path=="/api/datasets")out.putArray("datasets").addObject().put("name","science").put("description","Science data").put("uri",n.path("uri").asText+"science")
      else {
        val items=out.putArray("items")
        if(n.path("path").asText=="/")items.addObject().put("path","/folder").put("kind","folder")
        else {val item=items.addObject().put("path","/folder/table one.csv").put("kind","item");item.putArray("views").add("records");item.putObject("schema").put("type","records")}
      };out
    }
    try{
      val result=ChatCatalogContext.collect(roots,load,pool)
      val local=result.path("catalogs").get(0);val remote=result.path("catalogs").get(1)
      assert(local.path("complete").asBoolean)
      assert(local.path("datasets").get(0).path("items").get(1).path("uri").asText=="context:/science/folder/table%20one.csv")
      assert(remote.path("datasets").get(0).path("items").get(1).path("uri").asText=="dacp://node:123/science/folder/table%20one.csv")
    }finally pool.shutdownNow()
  }
  test("one slow catalog does not hide a fast catalog or block beyond the time budget"){
    val pool=Executors.newFixedThreadPool(2)
    val roots=Vector(Json.obj().put("uri","context:/"),Json.obj().put("uri","dacp://fast:123/"))
    try{
      val result=ChatCatalogContext.collect(roots,(_,n)=>{if(n.path("uri").asText=="context:/")Thread.sleep(5000);val out=Json.obj();out.putArray("datasets");out},pool,200)
      assert(result.path("catalogs").get(0).path("status").asText=="timeout")
      assert(result.path("catalogs").get(1).path("complete").asBoolean)
    }finally pool.shutdownNow()
  }
  test("large listings are bounded and marked incomplete"){
    val pool=Executors.newSingleThreadExecutor()
    try{
      val result=ChatCatalogContext.collect(Vector(Json.obj().put("uri","context:/")),(path,_)=>{
        val out=Json.obj()
        if(path=="/api/datasets")out.putArray("datasets").addObject().put("name","data").put("uri","context:/data")
        else{val a=out.putArray("items");for(i<-1 to 100)a.addObject().put("path","/file"+i).put("kind","item")};out
      },pool)
      val catalog=result.path("catalogs").get(0)
      assert(!catalog.path("complete").asBoolean)
      assert(catalog.path("datasets").get(0).path("items").size==80)
    }finally pool.shutdownNow()
  }
}
