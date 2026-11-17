package xstream.engine

import xstream.data._
import xstream.execution._
import java.nio.channels.{Channels,ServerSocketChannel,SocketChannel}
import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.nio.file.{Files,Path}
import java.io.{DataInputStream,DataOutputStream,FilterOutputStream}
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{VectorSchemaRoot,VarBinaryVector,VectorLoader,VectorUnloader}
import org.apache.arrow.vector.ipc.{ArrowStreamReader,ArrowStreamWriter}
import scala.jdk.CollectionConverters._

/** Uses exactly the host's authorized Item opening path; never accepts filesystem paths. */
final class ItemReadService(directory:Path,allocator:BufferAllocator,parent:StreamCancel,
  open:(InputBinding,StreamCancel)=>FrameSource) extends AutoCloseable {
  private val socket=directory.resolve(ItemReadProtocol.SocketName)
  private val server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)
  private val connections=java.util.concurrent.ConcurrentHashMap.newKeySet[SocketChannel]()
  @volatile private var closed=false
  server.bind(UnixDomainSocketAddress.of(socket))
  private val pool=new java.util.concurrent.ThreadPoolExecutor(4,4,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
    new java.util.concurrent.SynchronousQueue[Runnable](),new java.util.concurrent.ThreadFactory {
      def newThread(r:Runnable):Thread={val t=new Thread(r,"xstream-item-read");t.setDaemon(true);t}
    })
  private val worker=new Thread(()=>{
    while(!closed)try {
      val channel=server.accept();connections.add(channel)
      try pool.execute(new Runnable {def run():Unit={
        try serve(channel)catch {case scala.util.control.NonFatal(_)=>()}finally {connections.remove(channel);channel.close()}
      }})catch {case _:java.util.concurrent.RejectedExecutionException=>
        try ItemReadProtocol.write(new DataOutputStream(Channels.newOutputStream(channel)),Data.mapper.createObjectNode().put("ok",false).put("error","concurrent Item read limit reached"))
        finally {connections.remove(channel);channel.close()}
      }
    }catch {case scala.util.control.NonFatal(e)=>if(!closed)parent.fail(e)}
  },"xstream-item-access")
  worker.setDaemon(true);worker.start()
  private def serve(channel:SocketChannel):Unit={
    val token=new StreamCancel(()=>{parent.checkState();false},parent.remainingSeconds)
    val out=new DataOutputStream(Channels.newOutputStream(channel))
    var started=false
    try {
      parent.active();val n=ItemReadProtocol.read(new DataInputStream(Channels.newInputStream(channel)))
      require(n.isObject,"Item access request must be an object")
      val params=Data.mapper.convertValue(n,classOf[java.util.LinkedHashMap[String,Object]])
      val uri=ItemSourceParameters.address(params)
      val binding=InputBinding(uri=Some(uri),view=ItemSourceParameters.view(params),scan=ItemSourceParameters.query(params),selection=ItemSourceParameters.selection(params))
      val source=token.add(open(binding,token))
      try {
        val first=source.next().getOrElse(throw new IllegalArgumentException("missing Item stream header"))
        val h=StreamProtocol.header(first);require(h.path("type").asText=="start","invalid Item stream header")
        val repr=h.path("representation").asText
        val rawSchema=if(repr=="bytes")PluginSchemas.bytes else StreamProtocol.schema(h)
        val schema=if(Set("bytes","records")(repr))ArrowStreamMetadata.schema(rawSchema,repr,Data.mapper.createObjectNode())else rawSchema
        val root=VectorSchemaRoot.create(schema,allocator)
        val header=if(first.data.nonEmpty)Some(new ArrowStreamReader(new java.io.ByteArrayInputStream(first.data),allocator))else None
        var dictionary:org.apache.arrow.vector.dictionary.DictionaryProvider=header.orNull
        val dictionaries=new org.apache.arrow.vector.dictionary.DictionaryProvider {
          def lookup(id:Long)=if(dictionary==null)null else dictionary.lookup(id)
          def getDictionaryIds=if(dictionary==null)java.util.Collections.emptySet[java.lang.Long]()else dictionary.getDictionaryIds
        }
        // The Arrow writer owns its IPC output, but the socket also carries the
        // completion message after the writer and all Item resources are closed.
        val ipcOutput=new FilterOutputStream(out) {
          override def write(bytes:Array[Byte],offset:Int,length:Int):Unit=out.write(bytes,offset,length)
          override def close():Unit=flush()
        }
        val writer=new ArrowStreamWriter(root,dictionaries,ipcOutput)
        try {
          header.foreach(r=>require(!r.loadNextBatch(),"invalid Item dictionary header"))
          ItemReadProtocol.write(out,Data.mapper.createObjectNode().put("ok",true));started=true;writer.start()
          var ended=false
          while(!ended){
            token.active();val f=source.next().getOrElse(throw new IllegalArgumentException("truncated Item stream"))
            StreamProtocol.header(f).path("type").asText match {
              case "end"=>ended=true
              case "data" if repr=="bytes"=>root.clear();root.allocateNew();root.getVector(0).asInstanceOf[VarBinaryVector].setSafe(0,f.data);root.setRowCount(1);writer.writeBatch()
              case "data"=>
                val reader=new ArrowStreamReader(new java.io.ByteArrayInputStream(f.data),allocator)
                try {
                  require(ArrowStreamMetadata.logical(reader.getVectorSchemaRoot.getSchema)==ArrowStreamMetadata.logical(schema) && reader.loadNextBatch(),"invalid Item batch")
                  val batch=new VectorUnloader(reader.getVectorSchemaRoot).getRecordBatch
                  try new VectorLoader(root).load(batch)finally batch.close()
                  dictionary=reader;writer.writeBatch();require(!reader.loadNextBatch(),"multiple Item batches")
                }finally {dictionary=header.orNull;reader.close()}
              case _=>throw new IllegalArgumentException("unexpected Item stream event")
            }
          }
          writer.end()
        }finally {try writer.close()finally {try root.close()finally header.foreach(_.close())}}
      }finally source.close()
      token.active()
      out.flush()
      ItemReadProtocol.write(out,Data.mapper.createObjectNode().put("complete",true))
    }catch {case scala.util.control.NonFatal(e)=>if(!started)try ItemReadProtocol.write(out,Data.mapper.createObjectNode().put("ok",false).put("error",Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(4096)))catch{case scala.util.control.NonFatal(_)=>()}}
    finally token.close()
  }
  def close():Unit=if(!closed){closed=true;server.close();connections.asScala.foreach(_.close());pool.shutdownNow();worker.interrupt();worker.join(5000);pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);Files.deleteIfExists(socket)}
}
