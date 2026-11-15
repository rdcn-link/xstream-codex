package xstream.engine

import xstream.execution._
import xstream.util.Json
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable

/** One execution's owned artifacts; only internal attempts can reuse them. */
final class JobCheckpoints(store:CheckpointStore) extends AutoCloseable {
  private val keys=mutable.Map.empty[String,String]
  private var closed=false
  def available:Set[String]=synchronized {keys.keySet.toSet}
  def begin(step:String,ports:Set[String],check:()=>Unit=()=>()):CheckpointWriter=synchronized {
    require(!closed,"checkpoint execution closed");keys.remove(step).foreach(store.remove)
    val key=java.util.UUID.randomUUID().toString
    val writer=store.begin(key,ports,check)
    new CheckpointWriter {
      def append(port:String,frame:OperatorFrame):Unit=writer.append(port,frame)
      def commit():Unit=JobCheckpoints.this.synchronized {require(!closed,"checkpoint execution closed");writer.commit();keys(step)=key}
      def close():Unit=writer.close()
    }
  }
  def open(step:String,port:String,check:()=>Unit=()=>()):FrameSource=synchronized {
    require(!closed,"checkpoint execution closed")
    val data=store.get(keys(step)).getOrElse(throw new IllegalArgumentException("checkpoint unavailable"))
    try {
      val source=data.open(port,check)
      new FrameSource {def next():Option[OperatorFrame]=source.next();def close():Unit=try source.close()finally data.close()}
    }catch{case t:Throwable=>data.close();throw t}
  }
  def replay(step:String,ports:Set[String],cancel:StreamCancel)(emit:OperatorFrame=>Unit):Unit={
    val workers=ports.toVector.sorted.map { port=>StreamProtocol.thread(cancel){
      val in=cancel.add(open(step,port,()=>cancel.active()))
      try {var f=in.next();while(f.nonEmpty){cancel.active();emit(f.get);f=in.next()}}finally in.close()
    }}
    try workers.foreach(t=>{while(t.isAlive){cancel.active();t.join(50)};cancel.active()})
    finally {workers.foreach(_.join(5000));require(!workers.exists(_.isAlive),"checkpoint readers did not stop")}
  }
  def snapshot:JsonNode=synchronized {val n=Json.obj();keys.keys.toVector.sorted.foreach { step=>
    val data=store.get(keys(step)).getOrElse(throw new IllegalArgumentException("checkpoint unavailable"))
    try {val s=n.putObject(step).put("state","committed").put("bytes",data.bytes);val ps=s.putArray("outputs");data.ports.toVector.sorted.foreach(ps.add)}finally data.close()
  };n}
  def close():Unit=synchronized {
    if(!closed){keys.values.toVector.foreach(store.remove);keys.clear();closed=true}
  }
}

/** Backward dependency traversal stops at replayable complete-output boundaries. */
object CheckpointRecovery {
  def needed(plan:JobPlan,selected:Set[(String,String)],available:Set[String]):Set[String]={
    val needed=mutable.Set.empty[String]
    def visit(id:String):Unit=if(needed.add(id)){
      val t=plan.steps.find(_.id==id).get
      val connected=ExecutionPolicy.connected(plan).filter(_._1==id)
      val reusable=available(id) && connected.nonEmpty && connected.subsetOf(selected)
      if(!reusable)Jobs.dependencies(t).foreach(visit)
    }
    Jobs.roots(plan).foreach(visit);needed.toSet
  }
}
