package xstream.operator.executors

import _root_.xstream.execution.{OperatorFrame,OperatorFrames}

import _root_.xstream.data._

import xstream.operator.{Operator,OperatorRunner,OperatorBatchInput}
import com.fasterxml.jackson.databind.{JsonNode,ObjectMapper}
import java.io._
import java.nio.file.{Files,Path}
import java.net.URLClassLoader
import java.util.concurrent.{ArrayBlockingQueue,CompletableFuture,CountDownLatch,TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{VectorSchemaRoot,VarBinaryVector,VectorLoader,VectorUnloader}
import org.apache.arrow.vector.ipc.{ArrowStreamReader,ArrowStreamWriter}
import org.apache.arrow.vector.ipc.message.MessageSerializer
import org.apache.arrow.vector.ipc.ReadChannel
import scala.jdk.CollectionConverters._

/** One isolated JVM, one SDK instance per step, bounded in-memory Arrow links. */
private[executors] final class JvmGroupExecution(input:Path,output:OutputStream=System.out) {
  private val mapper=new ObjectMapper()
  private val group=mapper.readTree(Files.readAllBytes(input.resolve("group.json")))
  private val steps=group.path("steps").elements.asScala.toVector
  private val failure=new AtomicReference[Throwable]()
  private val threads=new java.util.concurrent.CopyOnWriteArrayList[Thread]()
  private val resources=new java.util.concurrent.ConcurrentLinkedDeque[AutoCloseable]()
  private val allocator=new RootAllocator()
  private val selected=group.path("outputs").fields.asScala.map(e=>(e.getValue.path("task").asText,e.getValue.path("port").asText)->e.getKey).toMap
  private val headers=new CountDownLatch(selected.size)
  private def event(h:JsonNode):Unit=output.synchronized{OperatorFrames.write(output,OperatorFrame(mapper.writeValueAsBytes(h)));output.flush()}
  private def state(task:String,value:String):Unit=event(mapper.createObjectNode().put("type","task_state").put("task",task).put("state",value).put("runtime_pid",ProcessHandle.current().pid()))
  private val recordPorts=java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  private def observe(task:String,direction:String,port:String,f:OperatorFrame,h:JsonNode):Unit={
    val n=mapper.createObjectNode().put("type","task_metric").put("task",task).put("direction",direction).put("port",port).put("phase",h.path("type").asText).put("payload_bytes",f.data.length)
    val key=task+"/"+direction+"/"+port
    if(h.has("representation")){n.put("representation",h.path("representation").asText);if(h.path("representation").asText=="records")recordPorts.add(key)}
    if(h.path("type").asText=="data" && recordPorts.contains(key)){
      val reader=new ArrowStreamReader(new ByteArrayInputStream(f.data),allocator)
      try{require(reader.loadNextBatch(),"missing record batch");n.put("records",reader.getVectorSchemaRoot.getRowCount)}finally reader.close()
    }
    event(n)
  }
  private def active():Unit={if(Thread.currentThread.isInterrupted)throw new InterruptedException();Option(failure.get()).foreach(throw _)}
  private def fail(t:Throwable):Unit=if(failure.compareAndSet(null,t)){
    resources.iterator.asScala.foreach(r=>try r.close()catch{case _:Throwable=>()});threads.asScala.filterNot(_==Thread.currentThread).foreach(_.interrupt())
  }
  private def thread(body: =>Unit):Thread={val t=new Thread(()=>try body catch{case t:Throwable=>fail(t)},"dacp-in-process");t.setDaemon(true);threads.add(t);t.start();t}
  private def schema(h:JsonNode)=MessageSerializer.deserializeSchema(new ReadChannel(java.nio.channels.Channels.newChannel(new ByteArrayInputStream(java.util.Base64.getDecoder.decode(h.path("schema").asText)))))
  private def validate(c:JsonNode,h:JsonNode):Unit={
    val repr=h.path("representation").asText;require(c.path("representations").elements.asScala.exists(_.asText==repr),"group port representation mismatch")
    val formats=c.path("formats");require(formats.isEmpty || formats.elements.asScala.exists(_.asText==h.path("format").asText),"group port format mismatch")
    if(c.has("schema"))require(_root_.xstream.data.ArrowStreamMetadata.logical(schema(h))==_root_.xstream.data.ArrowStreamMetadata.logical(schema(mapper.createObjectNode().put("schema",c.path("schema").asText))),"group port schema mismatch")
  }
  private def batchMetric(task:String,direction:String,port:String,batch:ArrowBatch):Unit={
    val n=mapper.createObjectNode().put("type","task_metric").put("task",task).put("direction",direction).put("port",port).put("phase","data")
      .put("payload_bytes",batch.root.getFieldVectors.asScala.map(_.getBufferSize.toLong).sum)
    if(recordPorts.contains(task+"/"+direction+"/"+port))n.put("records",batch.root.getRowCount)
    event(n)
  }
  private final case class Transfer(batch:Option[ArrowBatch],released:CompletableFuture[Unit])
  /** One outstanding borrowed batch. Producer waits for consumer advance before reusing buffers. */
  private class Link(contract:JsonNode,task:String,port:String) extends OperatorBatchInput {
    val metadata=new CompletableFuture[JsonNode]()
    private val events=new ArrayBlockingQueue[Transfer](1)
    @volatile private var ended=false
    private var current:Transfer=null
    private var header:JsonNode=null
    private var empty:VectorSchemaRoot=null
    private var initial:ArrowStreamReader=null
    resources.add(this)
    def sendHeader(f:OperatorFrame,h:JsonNode):Unit={
      active()
      h.path("type").asText match {
        case "start"=>
          validate(contract,h);header=h
          if(f.data.nonEmpty){initial=new ArrowStreamReader(new ByteArrayInputStream(f.data),allocator);initial.getVectorSchemaRoot;require(!initial.loadNextBatch(),"start contains business rows")}
          require(metadata.complete(h),"duplicate internal metadata")
        case "end"=>offer(Transfer(None,new CompletableFuture[Unit]()))
        case _=>throw new IllegalArgumentException("internal data must be Arrow batches")
      }
      observe(task,"inputs",port,f,h)
    }
    private def offer(value:Transfer):Unit={
      active();require(!ended,"internal stream closed")
      while(!events.offer(value,100,TimeUnit.MILLISECONDS)){active();require(!ended,"internal stream closed")}
    }
    def sendBatch(batch:ArrowBatch):Unit={
      require(header!=null,"batch before metadata")
      val expected=if(header.path("representation").asText=="bytes")PluginSchemas.bytes else schema(header)
      require(batch.root.getSchema.getFields==expected.getFields,"internal schema changed")
      val value=Transfer(Some(batch),new CompletableFuture[Unit]())
      batchMetric(task,"inputs",port,batch);offer(value)
      var waiting=true
      while(waiting){active();try {value.released.get(100,TimeUnit.MILLISECONDS);waiting=false}catch{case _:java.util.concurrent.TimeoutException=>()}}
    }
    private def release():Unit=if(current!=null){current.released.complete(());current=null}
    def loadNextBatch():Boolean={
      active();release()
      var value=events.poll(100,TimeUnit.MILLISECONDS)
      while(value==null){active();require(!ended,"internal stream closed");value=events.poll(100,TimeUnit.MILLISECONDS)}
      current=value
      if(value.batch.isEmpty){release();false}else true
    }
    def getVectorSchemaRoot:VectorSchemaRoot={
      if(current!=null && current.batch.nonEmpty)current.batch.get.root else {
        require(header!=null,"missing internal metadata")
        if(empty==null)empty=VectorSchemaRoot.create(if(header.path("representation").asText=="bytes")PluginSchemas.bytes else schema(header),allocator)
        empty
      }
    }
    private def currentDictionaries:org.apache.arrow.vector.dictionary.DictionaryProvider=
      if(current!=null)current.batch.map(_.dictionaries).orNull else initial
    private val dictionaryProvider=new org.apache.arrow.vector.dictionary.DictionaryProvider {
      def lookup(id:Long):org.apache.arrow.vector.dictionary.Dictionary={val p=currentDictionaries;if(p==null)null else p.lookup(id)}
      def getDictionaryIds:java.util.Set[java.lang.Long]={val p=currentDictionaries;if(p==null)java.util.Collections.emptySet[java.lang.Long]()else p.getDictionaryIds}
    }
    def dictionaries:org.apache.arrow.vector.dictionary.DictionaryProvider=dictionaryProvider
    def close():Unit=synchronized {if(!ended){ended=true;release();var value=events.poll();while(value!=null){value.released.completeExceptionally(new java.util.concurrent.CancellationException("link closed"));value=events.poll()};if(empty!=null){empty.close();empty=null};if(initial!=null){initial.close();initial=null}}}
  }
  def run():Unit={
    val work=Files.createTempDirectory("dacp-in-process-")
    val external=mapper.readTree(Files.readAllBytes(input.resolve("manifest.json"))).elements.asScala.map(n=>n.path("port").asText->n).toMap
    val links=steps.flatMap(s=>s.path("bindings").fields.asScala.filter(_.getValue.has("task")).map { e=>
      val binding=e.getValue;val upstream=(binding.path("task").asText,binding.path("port").asText)
      upstream->(s.path("id").asText,e.getKey,new Link(s.path("inputs").path(e.getKey),s.path("id").asText,e.getKey))
    }).toVector
    require(links.map(_._1).distinct.size==links.size,"internal fan-out unsupported")
    val bySource=links.toMap;val byTarget=links.map{case(_, (task,port,link))=>(task,port)->link}.toMap
    try{
      // Preflight every operator before any input is consumed or any task is invoked.
      val operators=steps.map { s=>
        val loader=new URLClassLoader(Array(Path.of(s.path("artifact").asText).toUri.toURL),getClass.getClassLoader);resources.add(loader)
        val entry=s.path("entrypoint").asText
        val instance=try loader.loadClass(entry+"$").getField("MODULE$").get(null)catch{case _:ClassNotFoundException=>loader.loadClass(entry).getDeclaredConstructor().newInstance()}
        require(instance.isInstanceOf[Operator],"group operator must implement the SDK Operator contract")
        s.path("id").asText->instance.asInstanceOf[Operator]
      }.toMap
      val workers=steps.map { step=>thread {
        val id=step.path("id").asText;val directory=Files.createDirectory(work.resolve(id));val manifest=mapper.createArrayNode()
        val batchInputs=scala.collection.mutable.Map.empty[String,OperatorBatchInput]
        val streams=step.path("bindings").fields.asScala.flatMap { e=>
          val port=e.getKey;val b=e.getValue
          val (meta,stream)=if(b.has("task")){
            val link=byTarget((id,port));var h:JsonNode=null
            while(h==null){active();try h=link.metadata.get(100,TimeUnit.MILLISECONDS)catch{case _:java.util.concurrent.TimeoutException=>()}}
            batchInputs(port)=link
            (h,None)
          }else{val h=external(b.path("port").asText);validate(step.path("inputs").path(port),h);val raw=Files.newInputStream(input.resolve(h.path("file").asText));resources.add(raw);(h,Some(raw))}
          val m=meta.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();m.put("port",port).put("file",port+".arrow");manifest.add(m)
          stream.map(port->_)
        }.toMap
        Files.write(directory.resolve("manifest.json"),mapper.writeValueAsBytes(manifest));Files.write(directory.resolve("params.json"),mapper.writeValueAsBytes(step.path("params")))
        val ports=step.path("outputs").fieldNames.asScala.toSet;var begun=Set.empty[String];var ended=Set.empty[String];var portKinds=Map.empty[String,String]
        state(id,"running")
        OperatorRunner.runBatches(directory,directory.resolve("params.json"),batchInputs.toMap,streams,f=>{
          active();val h=mapper.readTree(f.metadata);val port=h.path("port").asText;val key=(id,port)
          h.path("type").asText match {
            case "start"=>require(!begun(port),"duplicate group start");if(ports(port))validate(step.path("outputs").path(port),h);begun+=port;portKinds+=port->h.path("representation").asText
            case "data"=>require(begun(port) && !ended(port),"group data before metadata or after end")
            case "end"=>require(begun(port) && !ended(port),"invalid group end");ended+=port
            case _=>throw new IllegalArgumentException("invalid group output event")
          }
          observe(id,"outputs",port,f,h)
          bySource.get(key).foreach(_._3.sendHeader(f,h))
          selected.get(key).foreach { label=>
            val meta=h.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();meta.put("port",label)
            if(h.path("type").asText!="start")while(!headers.await(100,TimeUnit.MILLISECONDS))active()
            output.synchronized{OperatorFrames.write(output,OperatorFrame(mapper.writeValueAsBytes(meta),f.data));output.flush()}
            if(h.path("type").asText=="start")headers.countDown()
          }
        },(port,batch)=>{
          active();val key=(id,port)
          require(begun(port) && !ended(port),"batch before metadata or after end")
          batchMetric(id,"outputs",port,batch)
          bySource.get(key).foreach(_._3.sendBatch(batch))
          selected.get(key).foreach { label=>
            while(!headers.await(100,TimeUnit.MILLISECONDS))active()
            val chunks=if(portKinds(port)=="bytes"){
              val vector=batch.root.getVector(0).asInstanceOf[VarBinaryVector]
              (0 until batch.root.getRowCount).map(vector.get).toVector
            }else {
              val bytes=new ByteArrayOutputStream();val writer=new ArrowStreamWriter(batch.root,batch.dictionaries,bytes)
              try {writer.start();writer.writeBatch();writer.end();Vector(bytes.toByteArray)}finally writer.close()
            }
            chunks.foreach { bytes=>
              val h=mapper.createObjectNode().put("type","data").put("port",label)
              output.synchronized{OperatorFrames.write(output,OperatorFrame(mapper.writeValueAsBytes(h),bytes));output.flush()}
            }
          }
        })(operators(id).process)
        require(ended==begun && ports.subsetOf(ended),"incomplete group output")
        state(id,"succeeded")
      }}
      workers.foreach(t=>{while(t.isAlive){active();t.join(100)}})
      threads.asScala.foreach(t=>while(t.isAlive){active();t.join(100)})
      active()
    }catch{case t:Throwable=>fail(t);throw t}
    finally{
      resources.iterator.asScala.foreach(r=>try r.close()catch{case _:Throwable=>()})
      threads.asScala.foreach(t=>if(t.isAlive)t.interrupt());threads.asScala.foreach(_.join(1000))
      allocator.close();val files=Files.walk(work);try files.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))finally files.close()
    }
  }
}
