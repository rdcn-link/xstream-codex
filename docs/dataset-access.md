# Dataset, Item, and stream access

XStream uses `DataCatalog → PublishedDataset → DatasetAccess → DataView → ArrowDataStream`. A host supplies the catalog; the same data interfaces serve embedded engines, the Runner and Designer. DACP integrates these interfaces with its remote services; it does not own the data model.

## Ownership and capabilities

| Interface | Responsibility |
|---|---|
| `DataCatalog` | Lists datasets and opens caller-owned dataset leases |
| `PublishedDataset` | Owns dataset resources and creates request-scoped access |
| `DatasetAccess` | Lists Items, describes metadata and exposes supported views |
| `ItemAccess` | Optional backend handle for an object, file or cursor |
| `DataView` | Opens a selected view as an Arrow stream |
| `ArrowDataStream` | Produces schema and bounded Arrow batches during consumption |

Dataset descriptions are JSON strings with `title` and `description`. Item paths are logical paths, not storage credentials or physical filenames. Folder Items support child listing; ordinary folders do not expose content views. Structured files and archives can support both child listing and an original-file bytes view. Unknown sizes, counts and timestamps remain unknown.

## Reading a view

First describe the Item and choose an advertised view. Open it with the appropriate selection: records use `Scan`, bytes use `AllBytes`; scientific views preserve their declared dimensions and structures. Do not flatten arrays implicitly. See [Scala view examples](data-view-examples.md) for complete reading examples and [the data model](data-model.md) for CSV, compressed files and scientific Items.

`ArrowDataStream.consume(ArrowConsumer)` runs synchronously. The producer calls `start(schema, dictionaries)` and then `accept(ArrowBatch)` for each borrowed batch. Consumers must copy data retained beyond the callback. Check cancellation before reading and emitting; close streams, cursors and owned Arrow buffers on success and failure. Do not close caller-owned allocators or application connection pools. See [stream contracts](data-streams.md).

## Implementing a backend

Implement `DatasetProvider` and, where appropriate, `ItemDatasetAccess` with `list()`, `list(path)`, `describe(path)` and `openItem(path)`. The returned `ItemAccess` advertises supported views and implements their stream consumption. Backend plugins own authentication, pagination, timeouts, dependency libraries and cleanup. New backends do not require client changes or core format branches.

Register the provider in `META-INF/services/xstream.data.DatasetProvider`. Install its JAR and dependencies in the host's `plugin_directory`. Dynamic catalogs may return a `PublishedDataset` directly or use `CatalogContext.createDataset`. See [dataset providers](dataset-providers.md), [dynamic catalogs](dataset-catalogs.md) and [plugin architecture](plugins.md).

MongoDB collections and S3 objects are examples of possible backends, not bundled production connectors. A connector must explicitly define its schemas, view capabilities and scan behavior.

## Derived JSONL bytes

`ItemByteViews.wrap` adds a bytes view to records-only Items. A host uses this shared adapter when it wants this policy; a plugin may continue to implement records only. Native bytes always take priority. This conversion does not turn scientific arrays into records.

The derived representation is UTF-8 JSONL without a BOM, one object per record and a trailing newline:

```jsonl
{"station":"A","temperature":20.5,"quality":null}
{"station":"B","temperature":18.0,"quality":"ok"}
```

An empty stream yields zero bytes. Nested objects and lists preserve their structures; binary fields become Base64 strings. Non-finite numbers become strings. Unsupported values fail explicitly. The derived size is unknown until consumed; callers must not scan the data merely to display metadata.

Conversion emits bounded byte chunks and may split a record or UTF-8 character across batches. Decode the concatenated stream. It respects backpressure and cancellation without buffering the entire dataset or staging conversion output to disk. If consumption fails after partial output, that output is incomplete.
