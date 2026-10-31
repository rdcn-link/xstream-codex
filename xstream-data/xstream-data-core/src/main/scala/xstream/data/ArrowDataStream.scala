package xstream.data

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{FieldVector, VectorSchemaRoot}
import org.apache.arrow.vector.dictionary.{Dictionary, DictionaryProvider}
import org.apache.arrow.vector.types.pojo.{Field, Schema}
import scala.jdk.CollectionConverters._

/** A borrowed batch: its vectors and dictionaries are valid only inside accept.
  * Producers may reuse them on the next callback. Use copyTo for asynchronous use.
  */
final case class ArrowBatch(root: VectorSchemaRoot, dictionaries: DictionaryProvider = null) {
  require(root != null, "batch root required")
  def copyTo(allocator: BufferAllocator): OwnedArrowBatch = {
    def copyVector(source: FieldVector): FieldVector = {
      val target = source.getField.createVector(allocator)
      try {
        target.allocateNew()
        (0 until source.getValueCount).foreach(i => target.copyFromSafe(i, i, source))
        target.setValueCount(source.getValueCount)
        target
      } catch { case t: Throwable => target.close(); throw t }
    }
    val vectors = scala.collection.mutable.ArrayBuffer.empty[FieldVector]
    val dict = new DictionaryProvider.MapDictionaryProvider()
    try {
      root.getFieldVectors.asScala.foreach(v => vectors += copyVector(v))
      if (dictionaries != null) dictionaries.getDictionaryIds.asScala.foreach { id =>
        val d = dictionaries.lookup(id)
        require(d != null, "missing dictionary: " + id)
        dict.put(new Dictionary(copyVector(d.getVector), d.getEncoding))
      }
      new OwnedArrowBatch(new VectorSchemaRoot(root.getSchema, vectors.toVector.asJava, root.getRowCount), dict)
    } catch {
      case t: Throwable => vectors.reverseIterator.foreach(_.close()); dict.close(); throw t
    }
  }
}
/** Caller owns this snapshot and must close it after consumption. */
final class OwnedArrowBatch private[data](val root: VectorSchemaRoot, val dictionaries: DictionaryProvider.MapDictionaryProvider) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)
  def batch: ArrowBatch = { require(!closed.get(), "batch closed"); ArrowBatch(root, dictionaries) }
  def close(): Unit = if (closed.compareAndSet(false, true)) try root.close() finally dictionaries.close()
}
trait ArrowConsumer {
  /** Exactly once, including empty streams. Dictionary values are borrowed. */
  def start(schema: Schema, dictionaries: DictionaryProvider): Unit
  def accept(batch: ArrowBatch): Unit
}
/** Single consumption; synchronous callbacks provide backpressure without serialization.
  * Neither the stream nor its consumer owns the caller's allocator.
  */
trait ArrowDataStream extends AutoCloseable {
  def consume(consumer: ArrowConsumer): Unit
}
object ArrowDataStream {
  def apply(read: ArrowConsumer => Unit): ArrowDataStream = scoped((out, _) => read(out))
  def scoped(read: (ArrowConsumer, () => Unit) => Unit): ArrowDataStream = new ArrowDataStream {
    private val used = new AtomicBoolean(false)
    private val closed = new AtomicBoolean(false)
    def close(): Unit = closed.set(true)
    private def active(): Unit = if (closed.get()) throw new CancellationException("stream closed")
    def consume(out: ArrowConsumer): Unit = {
      require(out != null, "consumer required")
      require(!closed.get() && used.compareAndSet(false, true), "stream closed or already consumed")
      var schema: Schema = null
      val guarded = new ArrowConsumer {
        def start(s: Schema, dictionaries: DictionaryProvider): Unit = {
          active(); require(schema == null && s != null, "invalid or repeated schema")
          ArrowStreamMetadata.validate(s)
          validateDictionaries(s, dictionaries)
          schema = s; out.start(s, dictionaries)
        }
        def accept(batch: ArrowBatch): Unit = {
          active(); require(schema != null, "stream has no schema")
          require(batch.root.getSchema == schema, "stream schema changed")
          validateDictionaries(schema, batch.dictionaries)
          out.accept(batch)
        }
      }
      try { read(guarded, () => active()); require(schema != null, "stream has no schema") }
      finally close()
    }
  }
  private def validateDictionaries(schema: Schema, provider: DictionaryProvider): Unit = {
    def visit(field: Field): Unit = {
      val encoding = field.getDictionary
      if (encoding != null) require(provider != null && provider.lookup(encoding.getId) != null,
        "missing dictionary: " + encoding.getId)
      field.getChildren.asScala.foreach(visit)
    }
    schema.getFields.asScala.foreach(visit)
  }
}
object ArrowStreamMetadata {
  val View = "xstream.view"
  val Version = "xstream.view.version"
  val Descriptor = "xstream.descriptor"
  val CurrentVersion = "1"
  /** Compare logical columns and domain metadata independently of transport view metadata. */
  def logical(schema: Schema): Schema =
    new Schema(schema.getFields, Option(schema.getCustomMetadata).map(_.asScala.toMap)
      .getOrElse(Map.empty[String,String]).filterNot { case (key, _) => Set(View,Version,Descriptor)(key) }.asJava)
  def schema(base: Schema, view: String, descriptor: com.fasterxml.jackson.databind.JsonNode): Schema = {
    require(view != null && view.nonEmpty && descriptor != null && descriptor.isObject, "invalid view metadata")
    val original = Option(base.getCustomMetadata).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
    new Schema(base.getFields, (original ++ Map(View -> view, Version -> CurrentVersion,
      Descriptor -> Data.mapper.writeValueAsString(descriptor))).asJava)
  }
  def view(schema: Schema): String = { validate(schema); schema.getCustomMetadata.get(View) }
  def descriptor(schema: Schema): com.fasterxml.jackson.databind.JsonNode = {
    validate(schema); Data.mapper.readTree(schema.getCustomMetadata.get(Descriptor))
  }
  def validate(schema: Schema): Unit = {
    val m = schema.getCustomMetadata
    require(m != null && Option(m.get(View)).exists(_.nonEmpty), "missing stream view")
    require(m.get(Version) == CurrentVersion, "unsupported stream view version")
    val text = m.get(Descriptor)
    require(text != null && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024 * 1024,
      "missing or oversized stream descriptor")
    val descriptor = Data.mapper.reader().`with`(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text)
    require(descriptor != null && descriptor.isObject, "invalid stream descriptor")
  }
}
