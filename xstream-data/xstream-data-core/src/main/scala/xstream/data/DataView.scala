package xstream.data

import java.util.concurrent.atomic.AtomicBoolean
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.types.pojo.Schema

/** Borrowed blocks are valid only until accept returns. Copy explicitly to retain them. */
final case class ByteChunk(bytes: Array[Byte], offset: Int, length: Int) {
  require(offset >= 0 && length >= 0 && offset <= bytes.length - length, "invalid byte slice")
}
final case class RecordBatch(root: VectorSchemaRoot, dictionaries: DictionaryProvider = null)
trait DataDescriptor[K]
final case class BytesDescriptor(mediaType: String = "application/octet-stream") extends DataDescriptor[ByteChunk]
final case class RecordsDescriptor(schema: Schema, dictionaries: DictionaryProvider = null) extends DataDescriptor[RecordBatch]
trait DataConsumer[K] {
  /** Exactly once, before any chunks, including an empty stream. */
  def start(descriptor: DataDescriptor[K]): Unit
  def accept(chunk: K): Unit
}
final case class StreamContext(allocator: BufferAllocator, check: () => Unit = () => ())
/** Identity token for request selection; all view outputs are Arrow batches. */
class DataViewType[S](val name: String)
trait DataView[S] {
  def open(selection: S, context: StreamContext): ArrowDataStream
}
case object AllBytes
object DataViews {
  val Bytes = new DataViewType[AllBytes.type]("bytes")
  val Records = new DataViewType[Scan]("records")
  def when[S,T](requested:DataViewType[S],expected:DataViewType[T])(view: => DataView[T]):Option[DataView[S]] =
    if(requested eq expected)Some(view.asInstanceOf[DataView[S]])else None
  def apply[S](read: (S,StreamContext,ArrowConsumer) => Unit): DataView[S] = new DataView[S] {
    def open(selection:S,context:StreamContext):ArrowDataStream = ArrowDataStream.scoped { (out,active) =>
      val scoped=context.copy(check=()=>{active();context.check()})
      scoped.check(); read(selection,scoped,out)
    }
  }
  /** Standard parser helper. The public view exposes Arrow, never parser-specific blocks. */
  def blocks[K,S](read: (S,StreamContext,DataConsumer[K]) => Unit): DataView[S] =
    apply[S] { (selection,context,out) =>
      val writer=ArrowRepresentations.writer[K](context,out)
      try read(selection,context,writer) finally writer.close()
    }
}
