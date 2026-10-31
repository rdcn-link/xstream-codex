# Data views and Arrow streams

[中文](data-streams_zh.md)

A DataCatalog contains Datasets; each Dataset exposes logical Items. An Item may be browsable, readable, or both. A file container can expose original bytes and children simultaneously. Folder status alone does not determine readability.

## Public interface

`DatasetAccess.view(path, DataViewType[S])` discovers an optional `DataView[S]`. `open(selection, StreamContext)` returns a single-use `ArrowDataStream`. All views expose the same output interface: exactly one `start(schema, dictionaries)` callback followed by zero or more `accept(ArrowBatch)` callbacks. Empty streams still supply their schema. The schema remains constant during consumption.

```scala
val stream = access.requireView("/observations", DataViews.Records)
  .open(Scan(), StreamContext(allocator, () => cancellation.check()))
try stream.consume(new ArrowConsumer {
  def start(schema: org.apache.arrow.vector.types.pojo.Schema,
            dictionaries: org.apache.arrow.vector.dictionary.DictionaryProvider): Unit =
    println(schema)
  def accept(batch: ArrowBatch): Unit =
    println(batch.root.getRowCount)
}) finally stream.close()
```

Synchronous callbacks provide backpressure. Roots, vectors and dictionaries are borrowed until the callback returns; producers may reuse them. Use `batch.copyTo(allocator)` when a consumer needs an independently owned snapshot, and close that snapshot. Reference counting alone does not prevent mutation of a reused buffer. The caller owns the allocator. Producers acquire resources during consumption, release them in `finally` and call `context.check()` before reads and emissions. Closing during consumption prevents further callbacks; arbitrary blocking third-party I/O still requires its own cancellation mechanism.

The complete schema, representation, ownership and IPC contract is documented in [Arrow view streams](arrow-streams.md).

## Standard views

| Token | Selection | Arrow output |
| --- | --- | --- |
| `DataViews.Bytes` | `AllBytes` | Binary chunks in `data` rows |
| `DataViews.Records` | `Scan` | Ordinary typed columns |
| `ScientificViews.Arrays` | `ArraySelection` | Multidimensional blocks: origin, shape, stride and typed values |
| `ScientificViews.Sequences` | `SequenceSelection` | Named sequence chunks with offsets and final markers |

Arrays preserve rank and dimensions rather than becoming scalar records. An absent array section selects the whole variable; blocks always carry concrete coordinates. Sequence offsets count symbols. `DataViews.blocks` and `ArrowRepresentations.writer` let parsers emit bounded `ByteChunk`, `RecordBatch`, `ArrayChunk` or `SequenceChunk` values; the resulting public view still emits Arrow. `ArrowBlocks` is a convenience projection for these standard representations, not a second transport format.

Use `Scan` for projection, filtering and a global row limit. JDBC and Parquet retain their source optimizations; other readers use bounded filtering. Plain records preserve dictionaries. Scans over dictionary-encoded records remain explicitly unsupported. `BatchDatasetAccess` and `BatchItemAccess` adapt existing Arrow producers through `BatchReceiver`; they do not require a network listener or an IPC round trip.

Original file bytes take precedence. SQL tables expose Arrow IPC bytes. For records-only Items, `ItemByteViews` derives bounded UTF-8 JSONL bytes (`application/x-ndjson`), preserving nested values and decoding top-level dictionaries. A byte chunk need not align with a row or UTF-8 character. Errors do not flush buffered partial JSON. View conversion does not implicitly materialize output.

## Scientific formats

| Format | Browsing and views | Preservation |
| --- | --- | --- |
| CSV / JSONL | records and original bytes | Typed columns, nulls and nested values |
| NetCDF | groups and variables; variable array view | Rank, dimensions, coordinate references, units, fill values and attributes |
| FITS | HDUs; table records and image arrays | HDU identity, headers, axes, scaling and coordinate metadata |
| FASTA | original bytes and sequence view | Sequence identity, symbol offset, final marker and bounded chunks |
| JPEG | original bytes | Decode images inside an operator |

Array values are typed Arrow lists, one row per multidimensional block. Dense values use row-major order. Coordinate Items remain logical references, not copied coordinate arrays in every block. Raw numeric values and scaling/missing-value attributes remain explicit; no automatic flattening or silent conversion occurs.

## Plugins and network access

Implement `DatasetProvider`, `FormatHandler` or `StructuredFileHandler` and register the public no-argument class in `META-INF/services/<interface>`. Keep third-party dependencies and configuration validation in the plugin module. A structured reader returns a DatasetAccess over its logical children; storage paths must not leak into the logical namespace. See [plugins](plugins.md).

```yaml
datasets:
  climate:
    provider: my-netcdf
    source:
      root: /srv/climate
```

`my-netcdf` is an example external provider. It owns the `source` settings. A custom representation defines a shared `DataViewType[S]` token and emits its schema and Arrow batches directly; adding it does not require a core registry or an output codec. Tokens match by identity, not only by their names.

Optional `DataViewSupport` plugins parse/render request selections and provide bounded schema/sample presentation. Register them through `META-INF/services/xstream.data.DataViewSupport`. Their output is also ordinary Arrow; they do not encode or decode payload bytes. Built-in array and sequence support lives in `xstream-data-scientific-views`. Flight carries these typed columns directly, including descriptor metadata and empty schemas. Python `as_view`, `as_array` and `as_sequences` return an `ArrowViewReader` that iterates PyArrow RecordBatches.

Contract tests cover empty schemas, stable schemas, bounds, borrowed lifetimes, explicit snapshots, failure/cancellation cleanup, dictionary IPC, selection, nested browsing and exact scientific structure. See also [designer scientific views](designer-scientific.md).
