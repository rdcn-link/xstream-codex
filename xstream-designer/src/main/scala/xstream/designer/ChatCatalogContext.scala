package xstream.designer

import com.fasterxml.jackson.databind.JsonNode
import _root_.xstream.util.Json
import java.net.URI
import java.util.concurrent.{Callable,ExecutorCompletionService,ExecutorService,TimeUnit}
import scala.jdk.CollectionConverters._

/** Bounded metadata browsing; never opens an Item's content stream. */
private[designer] object ChatCatalogContext {
  def collect(roots:Vector[JsonNode],load:(String,JsonNode)=>JsonNode,pool:ExecutorService,budgetMs:Long=8000):JsonNode={
    val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budgetMs)
    val completion=new ExecutorCompletionService[(Int,JsonNode)](pool)
    val selected=roots.take(8)
    val results=scala.collection.mutable.Map.empty[Int,JsonNode]
    val futures=selected.zipWithIndex.flatMap{case(root,index)=>
      try Some(completion.submit(new Callable[(Int,JsonNode)]{def call()=(index,browse(root,load,deadline))}))
      catch{case _:java.util.concurrent.RejectedExecutionException=>results(index)=unavailable(root,"busy");None}
    }
    try {
      var left=futures.size
      while(left>0 && System.nanoTime()<deadline){
        val next=completion.poll(math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)
        if(next==null)left=0
        else{val(index,value)=next.get();results(index)=value;left-=1}
      }
    }finally futures.foreach(_.cancel(true))
    val out=Json.obj().put("truncated",roots.size>selected.size)
    val catalogs=out.putArray("catalogs")
    selected.zipWithIndex.foreach{case(root,index)=>catalogs.add(results.getOrElse(index,unavailable(root,"timeout")))}
    out
  }
  private def unavailable(root:JsonNode,status:String):JsonNode={val n=root.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();n.put("status",status);n.put("complete",false);n}
  private def browse(root:JsonNode,load:(String,JsonNode)=>JsonNode,deadline:Long):JsonNode={
    val out=root.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();out.put("status","available").put("complete",true)
    val datasets=out.putArray("datasets");var count=0;var byteCount=0
    def active=System.nanoTime()<deadline && !Thread.currentThread().isInterrupted
    try{
      val request=Json.obj().put("uri",root.path("uri").asText)
      val directory=load("/api/datasets",request)
      if(directory.has("complete") && !directory.path("complete").asBoolean)out.put("complete",false)
      val all=directory.path("datasets").elements.asScala.toVector
      if(all.size>12)out.put("complete",false)
      all.take(12).foreach{dataset=>
        if(active && count<80 && byteCount<65536){
          val ds=datasets.addObject().put("name",dataset.path("name").asText).put("uri",dataset.path("uri").asText).put("description",dataset.path("description").asText.take(2048))
          val items=ds.putArray("items");ds.put("complete",true)
          val queue=scala.collection.mutable.Queue(("/",0));val visited=scala.collection.mutable.Set.empty[String]
          try{
            while(queue.nonEmpty && active && count<80 && byteCount<65536){
              val(path,depth)=queue.dequeue()
              if(visited.add(path)){
                val response=load("/api/items",Json.obj().put("uri",dataset.path("uri").asText).put("path",path))
                response.path("items").elements.asScala.foreach{item=>
                  if(active && count<80 && byteCount<65536){
                    val copy=item.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
                    val encoded=new URI(null,null,"/"+item.path("path").asText.stripPrefix("/"),null).toASCIIString
                    copy.put("uri",dataset.path("uri").asText.stripSuffix("/")+encoded)
                    if(copy.toString.length>8192){copy.remove("schema");copy.put("schema_omitted",true)}
                    items.add(copy);count+=1;byteCount+=copy.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    if(item.path("kind").asText=="folder"){
                      if(depth<2)queue.enqueue((item.path("path").asText,depth+1))else ds.put("complete",false)
                    }
                  }else ds.put("complete",false)
                }
              }
            }
            if(queue.nonEmpty)ds.put("complete",false)
          }catch{case scala.util.control.NonFatal(_)=>ds.put("complete",false).put("status","unavailable")}
          if(!ds.path("complete").asBoolean)out.put("complete",false)
        }else out.put("complete",false)
      }
      if(!active)out.put("complete",false)
    }catch{case scala.util.control.NonFatal(_)=>out.put("status","unavailable").put("complete",false)}
    out
  }
}
