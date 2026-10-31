package xstream.data

import java.nio.{ByteBuffer, ByteOrder}
import org.apache.arrow.vector._
import org.apache.arrow.vector.complex.ListVector
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.types.pojo.Schema
import scala.jdk.CollectionConverters._

/** Bounded convenience projection of the standard Arrow representations for parser/SDK helpers.
  * This is not a wire format: the underlying stream always carries Arrow batches.
  */
object ArrowBlocks {
  def consume[K](stream: ArrowDataStream, out: DataConsumer[K]): Unit = stream.consume(consumer(out))
  def consumer[K](out: DataConsumer[K]): ArrowConsumer = new ArrowConsumer {
    private var description: DataDescriptor[_] = null
    def start(schema: Schema, dictionaries: DictionaryProvider): Unit = {
      val d = ArrowStreamMetadata.descriptor(schema)
      def attrs = Option(d.get("attributes")).map(_.fields.asScala.map(e => e.getKey -> e.getValue).toMap).getOrElse(Map.empty[String, com.fasterxml.jackson.databind.JsonNode])
      description = ArrowStreamMetadata.view(schema) match {
        case "bytes" => BytesDescriptor(d.path("media_type").asText("application/octet-stream"))
        case "records" => RecordsDescriptor(schema, dictionaries)
        case "array" =>
          val order = d.path("byte_order").asText
          require(Set("little", "big")(order), "invalid array byte order")
          require(d.path("dimensions").isArray, "missing array dimensions")
          val dimensions = d.path("dimensions").elements.asScala.map(x => Dimension(x.path("name").asText, x.path("length").asLong,
            Option(x.get("coordinate_item")).map(_.asText), Option(x.get("unit")).map(_.asText))).toVector
          ArrayDescriptor(dimensions, NumericEncoding(d.path("data_type").asText, if (order == "little") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN), attrs)
        case "sequence" => SequenceDescriptor(d.path("alphabet").asText, attrs)
        case other => throw new IllegalArgumentException("no block helper for view: " + other)
      }
      out.start(description.asInstanceOf[DataDescriptor[K]])
    }
    private def emit(value: Any): Unit = out.accept(value.asInstanceOf[K])
    def accept(batch: ArrowBatch): Unit = {
      val root = batch.root
      description match {
        case _: RecordsDescriptor => emit(RecordBatch(root, batch.dictionaries))
        case _: BytesDescriptor =>
          val v = root.getVector("data").asInstanceOf[VarBinaryVector]
          (0 until root.getRowCount).foreach { i => require(!v.isNull(i), "null byte chunk"); val b = v.get(i); emit(ByteChunk(b, 0, b.length)) }
        case _: SequenceDescriptor =>
          def text(name: String, i: Int): String = root.getVector(name).asInstanceOf[VarCharVector].getObject(i).toString
          (0 until root.getRowCount).foreach { i => emit(SequenceChunk(text("id", i), root.getVector("offset").asInstanceOf[BigIntVector].get(i), text("symbols", i), root.getVector("last").asInstanceOf[BitVector].get(i) != 0,
            if (root.getVector("description").isNull(i)) None else Some(text("description", i)))) }
        case d: ArrayDescriptor =>
          def longs(name: String, i: Int): Vector[Long] = root.getVector(name).asInstanceOf[ListVector].getObject(i).asScala.map(_.asInstanceOf[Number].longValue).toVector
          (0 until root.getRowCount).foreach { i =>
            val section = ArraySection(longs("origin", i), longs("shape", i), longs("stride", i)); section.validate(d.dimensions)
            val values = root.getVector("values").asInstanceOf[ListVector]
            val begin = values.getElementStartIndex(i); val end = values.getElementEndIndex(i)
            val size = (end.toLong - begin) * d.encoding.bytesPerValue
            require(size >= 0 && size <= ArrowRepresentations.MaxBlockBytes, "array block too large")
            val bytes = ByteBuffer.allocate(size.toInt).order(d.encoding.byteOrder)
            val data = values.getDataVector
            (begin until end).foreach { j =>
              require(!data.isNull(j), "null array element requires explicit mask representation")
              data match {
                case v: TinyIntVector => bytes.put(v.get(j))
                case v: UInt1Vector => bytes.put(v.get(j))
                case v: SmallIntVector => bytes.putShort(v.get(j))
                case v: UInt2Vector => bytes.putShort(v.get(j).toShort)
                case v: IntVector => bytes.putInt(v.get(j))
                case v: UInt4Vector => bytes.putInt(v.get(j))
                case v: BigIntVector => bytes.putLong(v.get(j))
                case v: UInt8Vector => bytes.putLong(v.get(j))
                case v: Float4Vector => bytes.putFloat(v.get(j))
                case v: Float8Vector => bytes.putDouble(v.get(j))
              }
            }
            bytes.flip(); emit(ArrayChunk(section, d.encoding, bytes))
          }
        case _ => throw new IllegalArgumentException("missing block descriptor")
      }
    }
  }
}
