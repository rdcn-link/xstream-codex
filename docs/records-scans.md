# Records scans

A records view accepts `Scan` when opened. Filtering, projection and row limits are data-layer operations, shared by local hosts and remote-service integrations.

## Plugin implementation contract

A provider may push supported operations into its source. It must still apply residual predicates and preserve the requested projection and limit. Unsupported expressions must fail explicitly rather than silently change results. `BatchDatasetAccess.readScan` supplies a bounded fallback. File formats can override `FormatHandler.scan` to optimize reading.

Check cancellation while reading and emitting. Stop once the result limit is reached and release cursors, streams and batches. Do not collect an entire dataset to implement a scan. Counts must be exact when provided; use an unknown count when exact counting is unavailable.

Scientific arrays are not implicitly records. Only views explicitly advertising records accept this scan contract. See [data views](data-streams.md), [dataset providers](dataset-providers.md) and [format handlers](format-handlers.md).

## Expression semantics

- References address top-level public Arrow field names, exactly and case sensitively. Nested field traversal, column-to-column comparisons, arithmetic, sorting, aggregation, and offset are outside this implementation.
- Supported comparisons are `eq`, `ne`, `lt`, `le`, `gt`, and `ge`, plus `in`, `is_null`, and `is_not_null`. Boolean expressions support `and`, `or`, and `not`.
- Comparisons with null records evaluate to unknown. Boolean logic has three values: true, false, unknown. Only true matches. For example, `NOT (temperature > 20)` also excludes null temperatures. Use explicit null checks to include them.
- Null literals and null members in `in` are invalid. Empty `in` is false, including for null records.
- Literals carry an explicit type and canonical text, preserving decimal/integer precision. Integer columns require integer literals within the column's range. Decimal columns accept integers and decimals representable at the declared precision and scale. Floating-point columns accept integers and finite floating literals. No string-to-number conversions occur.
- Strings compare case sensitively in Unicode scalar order, without locale collation or normalization. Booleans order false before true. Floating-point NaN is unequal to every ordinary literal and fails ordered comparisons; signed zeroes compare equal. NaN/infinite literals are rejected.
- Dates use calendar dates. Timezone-aware timestamps compare as UTC instants; timestamps without a timezone compare as local date-times. Mixing those categories is invalid. Python uses `date`, `datetime`, and `Decimal`; Scala uses `LocalDate`, `LocalDateTime`, `Instant`/`OffsetDateTime`, and `BigDecimal`.
- Binary, list, map, struct, and other unsupported types can be projected and tested for null, but cannot be compared with ordinary literals. Dictionary-encoded scan streams are currently rejected.

Unknown fields, duplicate/empty projections, invalid literals, negative limits, and malformed expressions fail explicitly. Validation occurs when reading the stream, before record batches are emitted; schema inference may read a sample. Predicates are bounded to 32 levels and 1,024 expression nodes, with at most 10,000 members per `in` expression.

Projection preserves the requested column order. Omit it to return all columns. A predicate may reference a column absent from the output. Limits are nonnegative signed 64-bit integers and apply globally across batches. Zero returns the projected schema without records. The server stops upstream consumption when the limit is reached, releasing source resources. Readers may have already buffered a batch, JDBC fetch window, or Parquet row group. Queries preserve source order; a stable ordering across independent reads is not guaranteed.

## Execution and source pushdown

| Source | Current behavior |
|---|---|
| JDBC SQL | Prepared predicates for integers, decimals, booleans, and null checks; source projection includes output and filter columns. JDBC maximum rows is set only when the complete predicate is handled. |
| DACP Parquet | Avro/Parquet column projection, plus supported signed-integer predicates passed to the Parquet reader for record and available row-group pruning. |
| CSV, NDJSON, Excel | Bounded batch filtering and projection on the server; early termination at the limit. |
| Contributed provider or native structured child | Common streaming fallback, or an equivalent plugin implementation of the scan contract. |

Pushdown is an optimization. The common evaluator checks the original predicate even after SQL or Parquet pushdown. Unsupported source predicates remain in that evaluator. Conjunctions may be partially pushed; disjunctions must remain a safe superset of all matching rows. A limit must never be pushed ahead of a residual filter. The SQL implementation leaves collation-sensitive string comparisons and floating-point comparisons to the common evaluator.

Authorization still applies to the public Item path. Scan requests contain public field names and literal values, never physical file paths, SQL identifiers, or executable query text. Treat predicates as potentially sensitive user data when adding plugin logging.
