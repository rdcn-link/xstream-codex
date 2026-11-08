package xstream.designer

import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable

/** Short-lived metadata snapshots, shared by conversations and isolated by configured context. */
private[designer] final class ChatContextCache(ttlSeconds:Long,clock:()=>Long=()=>System.nanoTime()) {
 require(ttlSeconds>=1 && ttlSeconds<=3600,"chat.context_ttl_seconds must be between 1 and 3600")
 private val entries=mutable.Map.empty[String,(Long,JsonNode)]
 def get(key:String)(load: => JsonNode):(JsonNode,Boolean)=synchronized {
  val now=clock()
  entries.get(key).filter(e=>now-e._1<java.util.concurrent.TimeUnit.SECONDS.toNanos(ttlSeconds)) match {
   case Some((_,value))=>(value.deepCopy[JsonNode](),true)
   case None=>
    val value=load
    entries.retain{case(_,entry)=>clock()-entry._1<java.util.concurrent.TimeUnit.SECONDS.toNanos(ttlSeconds)}
    entries(key)=(clock(),value.deepCopy[JsonNode]());(value,false)
  }
 }
}
