package xstream.execution.file

import xstream.execution._
import xstream.data.{Data,PluginSchemas}
import java.nio.file.{Files,Path,StandardOpenOption,StandardCopyOption,LinkOption}
import java.nio.channels.{FileChannel,WritableByteChannel}
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{VectorSchemaRoot,VarBinaryVector}
import org.apache.arrow.vector.ipc.{ArrowFileWriter,ArrowFileReader}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class FileCheckpointStoreProvider extends CheckpointStoreProvider {
  val id="file"
  def open(settings:Map[String,Object]):CheckpointStore={
    require(settings.keySet.subsetOf(Set("directory","max_bytes","max_checkpoint_bytes")) && settings.contains("directory"),"invalid file checkpoint settings")
    def number(k:String,d:Long):Long={val v=settings.get(k).map(_.toString).getOrElse(d.toString);require(v.matches("[0-9]+"),s"$k must be a positive integer");val n=v.toLong;require(n>0,s"$k must be positive");n}
    new FileCheckpointStore(Path.of(settings("directory").toString),number("max_bytes",1073741824L),number("max_checkpoint_bytes",268435456L))
  }
}
/** Arrow IPC File envelopes retain every original header, dictionary and payload byte. */
final class FileCheckpointStore(directory:Path,maxBytes:Long,maxCheckpointBytes:Long) extends CheckpointStore {
  require(maxBytes>0 && maxCheckpointBytes>0,"invalid checkpoint quota")
  Files.createDirectories(directory)
  private val lockChannel=FileChannel.open(directory.resolve(".writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
  private val lock=try Option(lockChannel.tryLock()).getOrElse(throw new IllegalStateException("checkpoint directory is already in use"))catch{case t:Throwable=>lockChannel.close();throw t}
  private val entries=mutable.Map.empty[String,Entry]
  private val writers=mutable.Set.empty[CheckpointWriter]
  private var used=0L
  private var closed=false
  private case class Entry(path:Path,ports:Set[String],sizes:Map[String,Long],hashes:Map[String,String],bytes:Long,var leases:Int=0)
  private def delete(p:Path):Unit={if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){val walk=Files.walk(p);try walk.iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists)finally walk.close()}}
  // This store is execution-scoped: no old Job may reuse data after host restart.
  private val stale=Files.list(directory)
  try stale.iterator.asScala.filter(p=>p.getFileName.toString.startsWith("checkpoint-") || p.getFileName.toString.startsWith(".pending-")).foreach(delete)
  catch{case t:Throwable=>lock.release();lockChannel.close();throw t}finally stale.close()
  private def keyValid(key:String):Unit=require(key.matches("[A-Za-z0-9_-]{1,128}"),"invalid checkpoint key")
  private def digest(p:Path,check:()=>Unit):String={
    val hash=MessageDigest.getInstance("SHA-256");val in=Files.newInputStream(p);val b=new Array[Byte](65536)
    try {var n=in.read(b);while(n>=0){check();if(n>0)hash.update(b,0,n);n=in.read(b)}}finally in.close()
    hash.digest().map(b=>f"${b&255}%02x").mkString
  }
  def begin(key:String,ports:Set[String],check:()=>Unit):CheckpointWriter=synchronized {
    require(!closed,"checkpoint store closed");keyValid(key)
    require(!entries.contains(key) && ports.nonEmpty && ports.forall(_.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}")),"invalid checkpoint outputs")
    val staging=Files.createTempDirectory(directory,".pending-")
    var bytes=0L;var done=false;var poisoned=false
    val phases=mutable.Map.empty[String,Int]
    final class Port(val port:String) extends AutoCloseable {
      val allocator=new RootAllocator(128L*1024*1024)
      val root=VectorSchemaRoot.create(PluginSchemas.bytes,allocator)
      val channel=FileChannel.open(staging.resolve(port+".arrow"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
      val limited=new WritableByteChannel {
        def isOpen:Boolean=channel.isOpen
        def close():Unit=()
        def write(b:ByteBuffer):Int={
          require(b.remaining().toLong<=maxBytes-used && b.remaining().toLong<=maxCheckpointBytes-bytes,"checkpoint quota exceeded")
          check();val n=channel.write(b);used+=n;bytes+=n;n
        }
      }
      val writer=new ArrowFileWriter(root,null,limited)
      var ended=false
      private var released=false
      def append(f:OperatorFrame):Unit={
        if(!phases.contains(port))writer.start()
        root.allocateNew();root.getVector(0).asInstanceOf[VarBinaryVector].setSafe(0,OperatorFrames.encode(f));root.setRowCount(1)
        writer.writeBatch();root.clear()
      }
      def finish():Unit={writer.end();channel.force(true);ended=true}
      def close():Unit={if(!released){released=true;try {if(ended)writer.close()}finally {try channel.close()finally {try root.close()finally allocator.close()}}}}
    }
    val outputs=mutable.Map.empty[String,Port]
    val result=new CheckpointWriter {
      def append(port:String,frame:OperatorFrame):Unit=FileCheckpointStore.this.synchronized {
        require(!closed && !done && !poisoned && ports(port),"checkpoint writer unavailable")
        try {
          require(frame.metadata.length<=OperatorFrames.maxHeader && frame.data.length<=OperatorFrames.maxData,"checkpoint frame exceeds limits")
          val h=StreamProtocol.header(frame);require(h.path("port").asText==port,"checkpoint port mismatch")
          val previous=phases.getOrElse(port,0)
          val next=h.path("type").asText match {
            case "start"=>require(previous==0 && h.path("representation").asText.nonEmpty,"invalid checkpoint start");1
            case "data"=>require(previous==1,"invalid checkpoint data");1
            case "end"=>require(previous==1 && frame.data.isEmpty,"invalid checkpoint end");2
            case _=>throw new IllegalArgumentException("checkpoint cannot store control events")
          }
          outputs.getOrElseUpdate(port,new Port(port)).append(frame);phases(port)=next
        }catch{case t:Throwable=>poisoned=true;throw t}
      }
      def commit():Unit=FileCheckpointStore.this.synchronized {
        require(!closed && !done && !poisoned && phases.keySet==ports && phases.values.forall(_==2),"incomplete checkpoint")
        try {
          outputs.values.foreach(_.finish());outputs.values.foreach(_.close());outputs.clear()
          val sizes=ports.map(p=>p->Files.size(staging.resolve(p+".arrow"))).toMap
          val hashes=ports.map(p=>p->digest(staging.resolve(p+".arrow"),check)).toMap
          val target=directory.resolve("checkpoint-"+key)
          Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE)
          entries(key)=Entry(target,ports,sizes,hashes,bytes);done=true;writers-=this
        }catch{case t:Throwable=>poisoned=true;throw t}
      }
      def close():Unit=FileCheckpointStore.this.synchronized {
        if(!done){
          var failure:Throwable=null
          outputs.values.foreach(p=>try p.close()catch{case scala.util.control.NonFatal(t)=>if(failure==null)failure=t else failure.addSuppressed(t)})
          outputs.clear()
          try delete(staging)catch{case scala.util.control.NonFatal(t)=>if(failure==null)failure=t else failure.addSuppressed(t)}
          if(failure!=null)throw failure
          used-=bytes;done=true;writers-=this
        }
      }
    }
    writers+=result;result
  }
  def get(key:String):Option[CheckpointData]=synchronized {
    require(!closed,"checkpoint store closed");keyValid(key)
    entries.get(key).map { entry=>
      entry.leases+=1
      new CheckpointData {
        private var released=false
        private val readers=mutable.Set.empty[FrameSource]
        val bytes=entry.bytes
        val ports=entry.ports
        def open(port:String,check:()=>Unit):FrameSource=FileCheckpointStore.this.synchronized {
          require(!closed && !released && ports(port),"checkpoint unavailable")
          val file=entry.path.resolve(port+".arrow")
          require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && Files.size(file)==entry.sizes(port) && digest(file,check)==entry.hashes(port),"checkpoint integrity failure")
          val allocator=new RootAllocator(128L*1024*1024)
          val reader=try new ArrowFileReader(FileChannel.open(file,StandardOpenOption.READ),allocator)catch{case t:Throwable=>allocator.close();throw t}
          val source=new FrameSource {
            private var ended=false
            private var phase=0
            def next():Option[OperatorFrame]=FileCheckpointStore.this.synchronized {
              require(!released && !closed,"checkpoint reader unavailable")
              check();if(ended)return None
              if(!reader.loadNextBatch()){require(phase==2,"truncated checkpoint");close();return None}
              val root=reader.getVectorSchemaRoot
              require(root.getSchema==PluginSchemas.bytes && root.getRowCount==1,"invalid checkpoint envelope")
              val f=OperatorFrames.decode(root.getVector(0).asInstanceOf[VarBinaryVector].get(0))
              val h=StreamProtocol.header(f);require(h.path("port").asText==port,"checkpoint port changed")
              h.path("type").asText match {
                case "start"=>require(phase==0,"duplicate checkpoint start");phase=1
                case "data"=>require(phase==1,"invalid checkpoint data")
                case "end"=>require(phase==1 && f.data.isEmpty,"invalid checkpoint end");phase=2
                case _=>throw new IllegalArgumentException("invalid checkpoint event")
              };Some(f)
            }
            def close():Unit=FileCheckpointStore.this.synchronized {if(!ended){ended=true;try reader.close()finally allocator.close()}}
          }
          readers+=source;source
        }
        def close():Unit=FileCheckpointStore.this.synchronized {if(!released){readers.foreach(_.close());entry.leases-=1;released=true}}
      }
    }
  }
  def remove(key:String):Unit=synchronized {
    keyValid(key);entries.get(key).foreach { e=>require(e.leases==0,"checkpoint still in use");delete(e.path);used-=e.bytes;entries-=key }
  }
  def close():Unit=synchronized {if(!closed){writers.toVector.foreach(_.close());entries.keys.toVector.foreach(remove);closed=true;try lock.release()finally lockChannel.close()}}
}
