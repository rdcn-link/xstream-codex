package xstream.data

import java.io.{InputStream, OutputStream}
import java.nio.channels.{ReadableByteChannel, SeekableByteChannel, WritableByteChannel}
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{VectorLoader, VectorSchemaRoot, VectorUnloader}
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.ipc.{ArrowFileReader, ArrowFileWriter, ArrowReader, ArrowStreamReader, ArrowStreamWriter, ArrowWriter}
import org.apache.arrow.vector.types.pojo.Schema

/** One IPC encoding for every view. There are no representation-specific wire codecs.
  * Readers own their channels; writers leave caller-owned channels open.
  */
object ArrowIpc {
  private def dictionaryIds(schema:Schema):java.util.Set[java.lang.Long]={
    val ids=new java.util.HashSet[java.lang.Long]()
    def visit(f:org.apache.arrow.vector.types.pojo.Field):Unit={
      if(f.getDictionary!=null)ids.add(f.getDictionary.getId)
      import scala.jdk.CollectionConverters._
      f.getChildren.asScala.foreach(visit)
    }
    import scala.jdk.CollectionConverters._
    schema.getFields.asScala.foreach(visit)
    ids
  }
  private class StreamWriter(root:VectorSchemaRoot,provider:DictionaryProvider,output:WritableByteChannel)
    extends ArrowStreamWriter(root,provider,output) {
    def initialize():Unit={start();ensureDictionariesWritten(provider,dictionaryIds(root.getSchema))}
  }
  private class FileWriter(root:VectorSchemaRoot,provider:DictionaryProvider,output:WritableByteChannel)
    extends ArrowFileWriter(root,provider,output) {
    def initialize():Unit={start();ensureDictionariesWritten(provider,dictionaryIds(root.getSchema))}
  }
  /** Empty IPC stream carrying schema and dictionary values, never business rows. */
  def header(schema:Schema,dictionaries:DictionaryProvider,allocator:BufferAllocator):Array[Byte]={
    val root=VectorSchemaRoot.create(schema,allocator)
    val output=new java.io.ByteArrayOutputStream()
    val writer=new StreamWriter(root,dictionaries,java.nio.channels.Channels.newChannel(output))
    try {writer.initialize();writer.end();output.toByteArray}finally {try writer.close()finally root.close()}
  }
  def readStream(input: InputStream, context: StreamContext): ArrowDataStream =
    read(() => new ArrowStreamReader(input, context.allocator), context, () => input.close())
  def readFile(input: SeekableByteChannel, context: StreamContext): ArrowDataStream =
    read(() => new ArrowFileReader(input, context.allocator), context, () => input.close())
  private def read(open: () => ArrowReader, context: StreamContext, closeInput: () => Unit): ArrowDataStream = new ArrowDataStream {
    private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
    private val used = new java.util.concurrent.atomic.AtomicBoolean(false)
    @volatile private var reader: ArrowReader = null
    def consume(out: ArrowConsumer): Unit = {
      require(!closed.get() && used.compareAndSet(false, true), "stream closed or already consumed")
      val stream = ArrowDataStream.scoped { (consumer, active) =>
        def check(): Unit = { active(); context.check(); if (closed.get()) throw new java.util.concurrent.CancellationException("stream closed") }
        check(); reader = open()
        val root = reader.getVectorSchemaRoot
        // IPC readers populate dictionary values while loading messages, not while reading schema.
        val first=if(dictionaryIds(root.getSchema).isEmpty)None else {check();Some(reader.loadNextBatch())}
        consumer.start(root.getSchema, reader)
        if(first.contains(true))consumer.accept(ArrowBatch(root, reader))
        if(!first.contains(false))while ({ check(); reader.loadNextBatch() }) consumer.accept(ArrowBatch(root, reader))
      }
      try stream.consume(out) finally { stream.close(); close() }
    }
    def close(): Unit = if (closed.compareAndSet(false, true)) {
      val current = reader
      if (current != null) current.close() else closeInput()
    }
  }
  def writeStream(stream: ArrowDataStream, output: OutputStream, context: StreamContext): Unit =
    write(stream, java.nio.channels.Channels.newChannel(output), context, file = false)
  def writeFile(stream: ArrowDataStream, output: WritableByteChannel, context: StreamContext): Unit =
    write(stream, output, context, file = true)
  private def write(stream: ArrowDataStream, output: WritableByteChannel, context: StreamContext, file: Boolean): Unit = {
    // ArrowWriter.close closes its channel: protect the caller-owned channel.
    val borrowed = new WritableByteChannel {
      def write(buffer: java.nio.ByteBuffer): Int = output.write(buffer)
      def isOpen: Boolean = output.isOpen
      def close(): Unit = ()
    }
    var root: VectorSchemaRoot = null
    var writer: ArrowWriter = null
    var currentDictionaries: DictionaryProvider = null
    val dictionaries = new DictionaryProvider {
      def lookup(id: Long): org.apache.arrow.vector.dictionary.Dictionary =
        if (currentDictionaries == null) null else currentDictionaries.lookup(id)
      def getDictionaryIds: java.util.Set[java.lang.Long] =
        if (currentDictionaries == null) java.util.Collections.emptySet[java.lang.Long]() else currentDictionaries.getDictionaryIds
    }
    try {
      stream.consume(new ArrowConsumer {
        def start(schema: Schema, initialDictionaries: DictionaryProvider): Unit = {
          currentDictionaries = initialDictionaries
          context.check(); root = VectorSchemaRoot.create(schema, context.allocator)
          if(file){val w=new FileWriter(root,dictionaries,borrowed);writer=w;w.initialize()}
          else {val w=new StreamWriter(root,dictionaries,borrowed);writer=w;w.initialize()}
        }
        def accept(batch: ArrowBatch): Unit = {
          context.check()
          currentDictionaries = batch.dictionaries
          import scala.jdk.CollectionConverters._
          val copy = if (batch.root.getFieldVectors.asScala.exists(v => !(v.getAllocator.getRoot eq context.allocator.getRoot)))
            Some(batch.copyTo(context.allocator)) else None
          val local = copy.map(_.batch).getOrElse(batch)
          try {
            currentDictionaries = local.dictionaries
            val record = new VectorUnloader(local.root).getRecordBatch
            try new VectorLoader(root).load(record) finally record.close()
            writer.writeBatch()
            root.clear()
          } finally copy.foreach(_.close())
        }
      })
      context.check(); writer.end()
    } finally {
      try { if (writer != null) writer.close() }
      finally { try { if (root != null) root.close() } finally stream.close() }
    }
  }
}
