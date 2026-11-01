# File format and container reference

[Data model](data-model.md) · [Reading examples](data-view-examples.md) · [Develop a format plugin](format-handlers.md)

Use this reference for built-in file readers, options and operational limits. Choose an Item and view using the data model, then look up its format here. For scientific structures and reading examples, see [DataView examples](data-view-examples.md).

## Formats and views

A format identifies the source representation; a view describes how it can be read. Clients use the advertised `views`, including for formats added later, rather than guessing capabilities from a filename. Format names are lowercase.

| Source | Format | Views |
|---|---|---|
| `.csv` | `csv` | `records`, `bytes` |
| `.jsonl`, `.ndjson` | `ndjson` | `records`, `bytes` |
| `.json` | `json` | `bytes` |
| `.zip`, `.ZIP` | `zip` | `bytes` |
| `.tar` | `tar` | `bytes` |
| `.gz` | `gzip` | `bytes` |
| `.tar.gz`, `.tgz` | `tar-gzip` | `bytes` |
| `.xls`, `.xlsx` | `excel` | `records`, `bytes` |
| XStream Parquet | `parquet` | `records`, `bytes` |
| SQL table | `sql` | `records`, `bytes` |
| Other files | `bin` | `bytes` |

The registry uses an explicit `format` override first, then compound container suffixes, registered signature checks, and case-insensitive extensions, with `bin` as fallback. Detection reads at most eight signature bytes; it does not validate the entire file. Invalid structured content fails when its records view is opened. A recognized file remains available unchanged through bytes. Parquet currently requires XStream Arrow schema footer metadata. Ordinary JSON has bytes only; publish newline-delimited `.json` files with `format: ndjson` to enable records. SQL uses format `sql`; its bytes view uses Arrow IPC.

### Browsing ZIP archives

Map a ZIP file using an ordinary file alias. The server publishes it as `kind=folder`, `format=zip`, with a `bytes` view returning the original archive. `list("/archive")` returns immediate children, while `list()` includes all archive entries; missing parent folders are inferred. Entry formats are selected by extension in listings and detected by existing readers when opened. ZIPs inside ZIPs remain `bin` data Items without recursive expansion. Creation and modification times are reported when the storage source provides them; folder sizes and record counts remain unknown.

```yaml
datasets:
  public:
    provider: mixed
    items:
      /archive: {file: /private/reports.ZIP}
```


Listings read the ZIP directory without decompressing entries. Opening, inferring schema, or counting stages only the requested entry into a temporary server file, cleaned after completion, failure, or cancellation; the first response for a large entry therefore waits for decompression. Archives allow at most 10,000 entries, 512 MiB decompressed per entry, and 2 GiB total declared size. Absolute paths, traversal, duplicate entries, and file/parent conflicts are rejected. Entries never extract into the source directory or follow archived symlinks. Readable container roots advertise bytes; ordinary folders advertise no readable views.

### TAR, GZIP, and compressed TAR

Like `zip`, formats `tar`, `gzip`, and `tar-gzip` publish folders with an original-file bytes view. `.tar.gz` and `.tgz` are recognized as single compressed TAR containers exposing TAR entries directly, without an intermediate `.tar` child. Extensions are case-insensitive; explicit `format` overrides suffix detection.

Plain `.gz` exposes one decompressed data child. Public alias `/orders.csv.gz` produces `/orders.csv.gz/orders.csv`; aliases without `.gz` use `content`. Private filenames from GZIP headers are ignored, and child modification time and listing size remain unknown. Children offer records and bytes according to their content format. Concatenated GZIP members form a single decompressed stream.


TAR listings scan sequentially; compressed TAR listings also decompress. Reading a selected entry scans the entire archive to validate its directory and checksums, staging only the requested file for existing readers. GZIP listings do not decompress the payload; reading stages its decompressed content. Temporary files are cleaned on completion, failure, or cancellation, and scans and decompression check cancellation. Indexes are not cached across requests.

As with ZIP, limits are 10,000 entries, 512 MiB decompressed per entry, and 2 GiB total declared size. TAR's decompressed stream allows an additional 16 MiB for headers, padding, and trailers. TAR accepts regular files and directories, rejects symbolic links, hard links, sparse files, devices, and other special entries, and checks headers and GZIP CRCs. Conventional leading `./` prefixes and root directory entries are normalized; other traversal, duplicate names, and file/parent conflicts are rejected. Folder sizes and record counts remain unknown; available timestamps are preserved. Nested containers remain binary data without recursive expansion. BZ2, XZ, and Zstandard decompression are not implemented.

### Reader options and Excel

File mappings accept these private reader options:

```yaml
datasets:
  office:
    provider: mixed
    items:
      /orders:
        file: /private/sales.xlsx
        format: excel
        options: {sheet: Orders, header_row: 2}
        schema:
          - {name: id, type: int64}
          - {name: amount, type: float64}
      /events:
        file: /private/events.json
        format: ndjson
```

`options.sheet` and `options.header_row` are validated by the Excel handler; the header row is one-based and defaults to 1. A single-sheet workbook selects its only sheet automatically; multiple sheets require an explicit selection. Publish multiple logical aliases to expose separate sheets. The original workbook is returned through bytes for each alias; records and counts refer only to its selected sheet. Sheet bindings and private filenames never appear in metadata or tickets.

Excel uses Apache POI 5.5.1 event readers for both XLS and XLSX. Empty headers become `column_N`, duplicate names receive deterministic `_2`, `_3` suffixes, and missing cells become null. Empty data rows are skipped; data beyond the header width fails. Cached formula values are read without recalculation; missing cached values become null when identifiable. Cell errors and encrypted or invalid workbooks fail with a format error. Numeric cells are int64 when integral and in range, otherwise float64; booleans and strings retain their values. Excel numeric dates remain serial numbers, with no implicit locale or timezone conversion. Optional `schema` supports ordered `name`/`type` entries using `int64`, `float64`, `bool`, and `utf8`/`string`. Without it, sampling is bounded by `batch_size * infer_sample`; later incompatible values fail instead of changing the schema.

Listings do not parse CSV, NDJSON, or Excel records; their counts remain unknown unless populated at publication. Opening records infers a schema from a bounded sample. Outside optional startup counting, only explicit `count` scans these files; Parquet can obtain its count cheaply from the footer. Excel parsers have a queue of at most 16 rows and stop on close/cancellation; schemas, current batches, individual cells, and POI shared-string dictionaries use additional memory. Large shared-string tables can still require substantial memory. No full worksheet is materialized.

For reader implementation and registration, see [format handler development](format-handlers.md).


## Item metadata

`creationTimeMs` and `lastModifiedTimeMs` are source-reported UTC Unix epoch milliseconds; `totalSizeBytes` is content size and `recordCount` is an exact logical count. Unknown values are `None`, distinct from a known zero. Unavailable timestamps are never replaced with the current time. Physical folders and readable archive roots may retain source times; virtual folders depend on their provider. Folder size and record count remain unknown, and listing never scans descendants to calculate them.

Ordinary listing does not count CSV, NDJSON, Excel or SQL records; explicit `count` scans records or executes a query. Parquet may use its footer. Metadata is an observation, not an atomic snapshot with subsequent reads.

### Populating counts at publication

Set `count_file_records_on_startup: true` to count mixed file mappings and initially discovered directory record files once while loading configuration and reuse those counts in ordinary browsing. It defaults to false. Counts are tied to file size and modification time; changes detected during or after counting leave the result unknown. Restarting recounts files. Explicit `count` still reads current content without changing saved metadata. SQL, archive children, and folders are excluded. Invalid record files fail startup when this option is enabled.

`plugin_directory` optionally selects a directory containing plugin and dependency JARs. It is loaded before registry initialization and requires a restart after changes. Reader-specific parameters go under `options`; handlers validate them. See [plugins](plugins.md) for the standalone API and structured-file contracts.

