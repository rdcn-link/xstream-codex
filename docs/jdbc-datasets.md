# JDBC datasets: PostgreSQL, types and stream semantics

[中文](jdbc-datasets_zh.md) · [Dataset configuration](dataset-providers.md)

Use the `sqldb` provider to map database tables to Items in a Dataset. This configuration serves the Runner, embedded applications and other hosts loading an XStream catalog. In Designer, place catalog configuration under `local_catalog`.

## PostgreSQL

The PostgreSQL and H2 JDBC drivers are bundled. Replace the private `warehouse` source under `sources` and update its explicit public mappings:

```yaml
datasets:
  sales:
    provider: sqldb
    source:
      connection: warehouse
    tables:
      /reports/orders: {table: orders}
      /clients: {table: customers}
sources:
  warehouse:
    provider: jdbc
    settings:
      url: jdbc:postgresql://db-host:5432/sales?sslmode=verify-full
      user_env: XSTREAM_DB_USER
      password_env: XSTREAM_DB_PASSWORD
      schema: public
      tables: [orders, customers]
      fetch_size: 256
      query_timeout_seconds: 60
```

Set those environment variables before starting the host, and configure the PostgreSQL driver's trusted server CA for `verify-full`. Use a database account with SELECT privileges on exposed tables and schema USAGE. Credentials remain on the server and are never sent to data consumers. Plain `user` / `password` fields are also accepted; environment fields take precedence and missing referenced variables fail configuration loading. Each private SQL source describes one database schema. Use `provider: mixed` with Item-level `sql` bindings to combine multiple sources if desired.

Use `provider: directory` with `source.root` for automatic file discovery, or configure `sqldb` / `mixed` public Item mappings and private `sources` explicitly. Private sources and tables are not published unless mapped. File mappings use `{file: /private/path.csv}`; SQL mappings use `{sql: warehouse, table: private_table}`. Names and paths visible to the client are independently chosen aliases. Each private SQL source supports:

| Field | Meaning |
|---|---|
| `url` | JDBC connection URL |
| `user`, `password` | Optional host-side credentials |
| `user_env`, `password_env` | Environment variable names for credentials |
| `schema` | Exact database schema; defaults to the driver's current schema |
| `tables` | Optional exact, case-sensitive table allowlist; omitted permits mapping any table in the schema; `[]` permits none; tables still require explicit public aliases |
| `fetch_size` | JDBC fetch window; defaults to `batch_size` |
| `query_timeout_seconds` | Statement timeout, default 60 seconds; positive |

SQL Items use `kind: data`, `format: sql`, and offer `records` and `bytes`. Table mappings must match JDBC metadata and are quoted as identifiers. Use [record scans](records-scans.md) for supported read-only filtering, projection and limits; consumers do not supply private table names or arbitrary database SQL. Private source settings are not Item metadata.

SQL-backed data Items leave `recordCount` unknown during normal listing and opening. Explicit `access.count("/reports/orders")` executes `COUNT(*)`, returning an exact count including zero for empty tables. `lastModifiedTimeMs` and `totalSizeBytes` remain absent because JDBC does not provide portable content modification times or serialized byte sizes. Counts may scan large tables and respect `query_timeout_seconds`; metadata and later reads are not a shared snapshot. All folder properties remain unknown, including for empty folders, without visiting child Items.

## Types and stream semantics

Supported SQL types map to Arrow as follows:

| SQL | Arrow |
|---|---|
| Integer types | signed int64 |
| REAL / FLOAT / DOUBLE | float64 |
| BOOLEAN / BIT | bool |
| Character types | utf8 |
| Binary types | binary |
| DECIMAL / NUMERIC with declared precision 1–38 and scale 0–precision | decimal128 |
| DATE | date32 |
| TIME | time64, microseconds |
| TIMESTAMP | timestamp, microseconds, no timezone |
| TIMESTAMP WITH TIME ZONE | timestamp, microseconds, UTC |

Nulls and JDBC column nullability are preserved. Sub-microsecond temporal precision is truncated. Arrays, LOBs, arbitrary vendor-specific types, unconstrained decimals, and time-with-time-zone are currently rejected explicitly; no silent type coercion is performed. For another JDBC engine, provide its driver as a plugin dependency or on the host classpath and validate schema metadata, type mapping and cursor behavior before use.

`records` streams table records directly. A table has no original file, so `bytes` provides an **Arrow IPC Stream serialization** of those records, readable incrementally with an Arrow IPC reader. It is not CSV, SQL text or a database backup. Choose a records-capable operator for table transformations. See [DataView examples](data-view-examples.md) for Scala reading APIs.

Each request owns its JDBC connection, statement and result set. Reads request a read-only transaction with forward-only cursors. PostgreSQL uses autocommit=false and a positive fetch size to avoid buffering the entire result ([driver documentation](https://jdbc.postgresql.org/documentation/query/)). Arrow allocation is bounded by batch size; JDBC buffering depends on the driver, and individual large values still occupy memory. SQL results have no implicit row order. Metadata discovery and stream reads use separate connections, so metadata and data are not a shared snapshot. Each reopened stream starts a new read, without retries, checkpoints or deduplication. Cancellation is checked between fetches/batches; an in-flight database operation may run until the driver/query timeout.

## Read in a Job

After setting the database credential environment variables, run this Job with the configured catalog:

```yaml
name: Read database orders
steps:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/sales/reports/orders
      view: records
outputs:
  result: read
```

The example path `/reports/orders` comes from the explicit mapping above. For parameters, variables and execution commands, see [Jobs](jobs.md) and the [quick start](quickstart.md).
