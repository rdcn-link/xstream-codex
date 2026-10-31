package xstream.data

import java.nio.{ByteBuffer, ByteOrder}
import org.apache.arrow.vector._
import org.apache.arrow.vector.complex.ListVector
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

/** Standard representation writers. Format plugins can emit blocks without owning Arrow vectors.
  * New representations can instead emit Arrow batches directly; no output codec registration is needed.
  */
object ArrowRepresentations {
  val MaxBlockBytes = 16 * 1024 * 1024
  private def field(name: String, t: ArrowType, nullable: Boolean = false): Field =
    new Field(name, new FieldType(nullable, t, null), null)
  private def list(name: String, t: ArrowType): Field =
    new Field(name, new FieldType(false, ArrowType.List.INSTANCE, null), List(field("item", t)).asJava)
  private val int64 = new ArrowType.Int(64, true)
  private def numeric(e: NumericEncoding): ArrowType = e.dataType match {
    case "float32" => new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
    case "float64" => new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
    case name => new ArrowType.Int(e.bytesPerValue * 8, !name.startsWith("u"))
  }
  def descriptor(d: DataDescriptor[_]): JsonNode = {
    val n = Data.mapper.createObjectNode()
    d match {
      case b: BytesDescriptor => n.put("media_type", b.mediaType)
      case _: RecordsDescriptor => ()
      case a: ArrayDescriptor =>
        n.put("data_type", a.encoding.dataType)
        n.put("byte_order", if (a.encoding.byteOrder == ByteOrder.LITTLE_ENDIAN) "little" else "big")
        val dims = n.putArray("dimensions")
        a.dimensions.foreach { d =>
          val x = dims.addObject().put("name", d.name).put("length", d.length)
          d.coordinateItem.foreach(x.put("coordinate_item", _)); d.unit.foreach(x.put("unit", _))
        }
        val attrs = n.putObject("attributes"); a.attributes.foreach { case (k, v) => attrs.set[JsonNode](k, v) }
      case s: SequenceDescriptor =>
        n.put("alphabet", s.alphabet)
        val attrs = n.putObject("attributes"); s.attributes.foreach { case (k, v) => attrs.set[JsonNode](k, v) }
      case _ => throw new IllegalArgumentException("emit Arrow batches directly for custom representations")
    }
    n
  }
  def schema(d: DataDescriptor[_]): Schema = {
    val (name, base) = d match {
      case _: BytesDescriptor => "bytes" -> PluginSchemas.bytes
      case r: RecordsDescriptor => "records" -> r.schema
      case a: ArrayDescriptor => "array" -> new Schema(List(list("origin", int64), list("shape", int64), list("stride", int64), list("values", numeric(a.encoding))).asJava)
      case _: SequenceDescriptor => "sequence" -> new Schema(List(field("id", ArrowType.Utf8.INSTANCE), field("offset", int64), field("symbols", ArrowType.Utf8.INSTANCE), field("last", ArrowType.Bool.INSTANCE), field("description", ArrowType.Utf8.INSTANCE, true)).asJava)
      case _ => throw new IllegalArgumentException("emit Arrow batches directly for custom representations")
    }
    ArrowStreamMetadata.schema(base, name, descriptor(d))
  }
  /** The sink closes only its own vectors. It never closes record batches supplied by its caller. */
  def writer[K](context: StreamContext, out: ArrowConsumer): DataConsumer[K] with AutoCloseable = new DataConsumer[K] with AutoCloseable {
    private var description: DataDescriptor[K] = null
    private var root: VectorSchemaRoot = null
    private var outputSchema: Schema = null
    def start(d: DataDescriptor[K]): Unit = {
      context.check(); require(description == null && d != null, "invalid or repeated descriptor")
      description = d; outputSchema = schema(d)
      d match {
        case r: RecordsDescriptor => out.start(outputSchema, r.dictionaries)
        case _ => root = VectorSchemaRoot.create(outputSchema, context.allocator); out.start(outputSchema, null)
      }
    }
    def accept(value: K): Unit = {
      context.check(); require(description != null, "missing descriptor")
      if (root != null) { root.clear(); root.allocateNew() }
      (description, value) match {
        case (r: RecordsDescriptor, b: RecordBatch) =>
          require(b.root.getSchema == r.schema, "record schema changed")
          val borrowed = new VectorSchemaRoot(outputSchema, b.root.getFieldVectors, b.root.getRowCount)
          out.accept(ArrowBatch(borrowed, b.dictionaries))
        case (_: BytesDescriptor, b: ByteChunk) =>
          require(b.length <= MaxBlockBytes, "byte chunk too large")
          root.getVector(0).asInstanceOf[VarBinaryVector].setSafe(0, b.bytes, b.offset, b.length)
          root.setRowCount(1); out.accept(ArrowBatch(root))
        case (s: SequenceDescriptor, b: SequenceChunk) =>
          val symbols = b.symbols.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          require(symbols.length <= MaxBlockBytes, "sequence chunk too large")
          def text(i: Int, v: String): Unit = root.getVector(i).asInstanceOf[VarCharVector].setSafe(0, v.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          text(0, b.id); root.getVector(1).asInstanceOf[BigIntVector].setSafe(0, b.offset)
          root.getVector(2).asInstanceOf[VarCharVector].setSafe(0, symbols)
          root.getVector(3).asInstanceOf[BitVector].setSafe(0, if (b.last) 1 else 0)
          b.description.foreach(text(4, _))
          root.setRowCount(1); out.accept(ArrowBatch(root))
        case (a: ArrayDescriptor, b: ArrayChunk) =>
          require(a.encoding == b.encoding, "array encoding changed")
          b.section.validate(a.dimensions)
          require(b.values.remaining <= MaxBlockBytes, "array block too large")
          Vector(b.section.origin, b.section.shape, b.section.stride).zipWithIndex.foreach { case (values, i) =>
            val v = root.getVector(i).asInstanceOf[ListVector]; v.startNewValue(0)
            val child = v.getDataVector.asInstanceOf[BigIntVector]
            values.zipWithIndex.foreach { case (x, j) => child.setSafe(j, x) }
            v.endValue(0, values.size); child.setValueCount(values.size)
          }
          val values = root.getVector(3).asInstanceOf[ListVector]; values.startNewValue(0)
          val child = values.getDataVector
          val bytes = b.values.duplicate().order(b.encoding.byteOrder)
          var i = 0
          while (bytes.hasRemaining) {
            if ((i & 4095) == 0) context.check()
            child match {
              case v: TinyIntVector => v.setSafe(i, bytes.get())
              case v: UInt1Vector => v.setSafe(i, bytes.get().toInt & 255)
              case v: SmallIntVector => v.setSafe(i, bytes.getShort())
              case v: UInt2Vector => v.setSafe(i, bytes.getShort().toInt & 65535)
              case v: IntVector => v.setSafe(i, bytes.getInt())
              case v: UInt4Vector => v.setSafe(i, bytes.getInt())
              case v: BigIntVector => v.setSafe(i, bytes.getLong())
              case v: UInt8Vector => v.setSafe(i, bytes.getLong())
              case v: Float4Vector => v.setSafe(i, bytes.getFloat())
              case v: Float8Vector => v.setSafe(i, bytes.getDouble())
            }
            i += 1
          }
          values.endValue(0, i); child.setValueCount(i)
          root.setRowCount(1); out.accept(ArrowBatch(root))
        case _ => throw new IllegalArgumentException("block does not match stream descriptor")
      }
    }
    def close(): Unit = if (root != null) { root.close(); root = null }
  }
}
