package xstream.data.process

import xstream.data._
import java.nio.file.{Files,Path}
import java.util.concurrent.TimeUnit
import java.io.{DataOutputStream,InputStream}
import java.nio.ByteBuffer
import com.fasterxml.jackson.databind.JsonNode
import scala.util.control.NonFatal

/** Private, bounded native-reader bridge. It never accepts source-provided commands or paths. */
final class ReaderProcess private(val home:Path,val process:Process,val maxMessage:Int,val timeoutSeconds:Int) extends AutoCloseable {
 private val input=process.getInputStream
 private val output=new DataOutputStream(process.getOutputStream)
 private var closed=false
 def send(message:JsonNode):Unit={
  require(!closed,"reader closed")
  val bytes=Data.mapper.writeValueAsBytes(message);require(bytes.length<=65536,"reader request too large")
  output.writeInt(bytes.length);output.write(bytes);output.flush()
 }
 private def bytes(length:Int,check:()=>Unit):Array[Byte]={
  val data=new Array[Byte](length);var offset=0
  val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong)
  while(offset<length){
   check()
   if(System.nanoTime()>deadline)throw new FormatFailure("Native format reader timed out")
   val available=input.available()
   if(available>0){val count=input.read(data,offset,math.min(length-offset,available));if(count<0)throw new FormatFailure("Native format reader failed");offset+=count}
   else if(!process.isAlive)throw new FormatFailure("Native format reader failed")
   else Thread.sleep(10)
  };data
 }
 def receive(check:()=>Unit):JsonNode={
  val size=ByteBuffer.wrap(bytes(4,check)).getInt
  require(size>0 && size<=maxMessage,"native reader response exceeds limit")
  val result=Data.mapper.readTree(bytes(size,check))
  if(result.has("error"))throw new FormatFailure("Native format reader rejected the input")
  result
 }
 def close():Unit=synchronized{if(!closed){closed=true
  try{output.close()}catch{case NonFatal(_)=>()}
  process.destroy()
  try{if(!process.waitFor(500,TimeUnit.MILLISECONDS)){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS)}}catch{case _:InterruptedException=>process.destroyForcibly();Thread.currentThread.interrupt()}
  try input.close() finally {val paths=Files.walk(home);try{import scala.jdk.CollectionConverters._;paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))}finally paths.close()}
 }}
}
object ReaderProcess {
 val CommonOptions=Set("chunk_bytes","max_source_bytes","worker_timeout_seconds","worker_memory_mb")
 def number(options:FileOptions,key:String,default:Long,min:Long,max:Long):Long=options.parameters.get(key).map{
  case n:Number=>val value=n.longValue;require(n.doubleValue==value.toDouble && value>=min && value<=max,"invalid reader limit");value
  case _=>throw new IllegalArgumentException("invalid reader limit")
 }.getOrElse(default)
 def validate(options:FileOptions,extra:Set[String]=Set.empty):Unit={
  require(options.columns.isEmpty && options.parameters.keySet.subsetOf(CommonOptions++extra),"invalid native reader options")
  number(options,"chunk_bytes",65536,8,16*1024*1024)
  number(options,"max_source_bytes",1024L*1024*1024,1,1024L*1024*1024*1024)
  number(options,"worker_timeout_seconds",60,1,3600)
  number(options,"worker_memory_mb",512,128,16384)
 }
 def open(source:ByteSource,options:FileOptions,python:String,script:String,mode:String,check:()=>Unit):ReaderProcess={
  validate(options);check()
  val home=Files.createTempDirectory("xstream-reader-")
  var child:Process=null
  try{
   val max=number(options,"max_source_bytes",1024L*1024*1024,1,1024L*1024*1024*1024)
   source.size.foreach(s=>require(s<=max,"source exceeds native reader staging limit"))
   // Always stage one file: never give native drivers access to source siblings or private filenames.
   val path=home.resolve("source")
   val in=source.open(check)
   try{val out=Files.newOutputStream(path);try{val b=new Array[Byte](65536);var total=0L;var n=0;do{check();n=in.read(b);if(n>0){total=Math.addExact(total,n);require(total<=max,"source exceeds native reader staging limit");out.write(b,0,n)}}while(n>=0)}finally out.close()}finally in.close()
   val builder=new ProcessBuilder(python,"-u","-c",script,path.toString,mode,number(options,"worker_memory_mb",512,128,16384).toString)
   builder.directory(home.toFile);builder.redirectError(ProcessBuilder.Redirect.DISCARD)
   val env=builder.environment();Seq("PYTHONPATH","PYTHONHOME","GDAL_DRIVER_PATH","GDAL_CONFIG_FILE","HDF5_PLUGIN_PATH").foreach(env.remove)
   env.put("PYTHONNOUSERSITE","1");env.put("OPENBLAS_NUM_THREADS","1");env.put("OMP_NUM_THREADS","1");env.put("PROJ_NETWORK","OFF");env.put("HDF5_PLUGIN_PATH",home.resolve("no-plugins").toString);env.put("HDF5_PLUGIN_PRELOAD","::")
   child=builder.start()
   new ReaderProcess(home,child,32*1024*1024,number(options,"worker_timeout_seconds",60,1,3600).toInt)
  }catch{case t:Throwable=>if(child!=null)child.destroyForcibly();val paths=Files.walk(home);try{import scala.jdk.CollectionConverters._;paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))}finally paths.close();throw t}
 }
 def script(owner:Class[_],name:String):String={val in=owner.getResourceAsStream(name);require(in!=null,"missing reader resource");try new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)finally in.close()}
 /** Sanitize native/library errors but preserve cancellation and thread interruption. */
 def guarded[A](f: => A):A=try f catch{
  case e:java.util.concurrent.CancellationException=>throw e
  case e:InterruptedException=>Thread.currentThread.interrupt();throw e
  case e:FormatFailure=>throw e
  case NonFatal(_)=>throw new FormatFailure("Native format reader failed")
 }
}
