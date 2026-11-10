# Galaxy streaming operators

[中文](README_zh.md)

200 source-published Scala operators adapt reviewed Galaxy Devteam, Community and IUC tools for Arrow bytes streams. This page covers twenty text and core genomics adapters; the [complete extension reference](../../../docs/galaxy-operators.md) describes the remaining 180 text, table and biological adapters, their supported scope and limitations. Original scripts, XML definitions, golden fixtures and licenses are retained in `upstream/` and `upstream-extended/` and `upstream-dependencies/`; the corresponding manifests record pinned revisions and SHA-256 hashes. These are independent adaptations of selected tools, not the complete Galaxy tool collection or a Galaxy workflow importer.

## Choose an operator

For the remaining 180 adapters, see [Galaxy streaming operator adapters](../../../docs/galaxy-operators.md).

The twenty operators below use version `1.0` (`galaxy_tabular_to_fasta`: `1.0.1`), one `source` bytes input and one `result` bytes output. Text is UTF-8. They implement structure inference and bounded output samples using the JVM SDK. A `bytes` output describes tabular text; these tools do not claim an Arrow records schema. Connect a suitable text/TSV parser when records are required. The shared runtime is `jvm-arrow@2.1`; artifacts are thin JARs with no new runtime dependencies.

| Operator | Parameters (defaults) | Behavior / upstream tool |
| --- | --- | --- |
| `galaxy_change_case` | `columns: c1`, `delimiter: tab`, `casing: upper` | Change selected fields, output TSV / `change_case/changeCase.pl` |
| `galaxy_condense_characters` | `character: tab` | Collapse repeated characters, output text / `condense_characters/condense_characters.pl` |
| `galaxy_convert_delimiters` | `character: tab` | Trim lines, replace delimiter runs with tabs, output TSV / `convert_characters/convert_characters.py` |
| `galaxy_cut_columns` | `columns: c1`, `delimiter: tab` | Select/reorder/repeat fields, skip `#` lines, fill missing fields with `.`, output TSV / `cut_columns/cutWrapper.pl` |
| `galaxy_merge_columns` | `columns: c1` | Append concatenated selected fields without a separator, skip missing fields; comments and blank lines remain, output TSV / `merge_cols/mergeCols.py` |
| `galaxy_add_column` | `value: "1"`, `iterate: false` | Append a constant; with iteration a nonnegative integer increases from its value, other values become `value-0`, `value-1`, …; output TSV / `add_value/fixedValueColumn.pl` |
| `galaxy_fasta_length` | `keep_first: 0`, `id_only: false` | Count characters per sequence, emit title/identifier and length as TSV / `fasta_compute_length/fasta_compute_length.py` |
| `galaxy_fasta_filter_length` | `min_length: 0`, `max_length: 0` | Keep entries within inclusive length bounds; zero maximum removes the upper threshold / `fasta_filter_by_length/fasta_filter_by_length.py` |
| `galaxy_fasta_to_tabular` | `keep_first: 0`, `description_columns: 1` | Emit title fields and concatenated sequence as TSV / `fasta_to_tabular/fasta_to_tabular.py` |
| `galaxy_remove_beginning` | `lines: 10` | Discard first N lines, preserve remaining line endings / `remove_beginning/remove_beginning.pl` |
| `galaxy_head` | `lines: 10` | Return first N lines and drain remaining input / `show_beginning/headWrapper.pl` |
| `galaxy_tail` | `lines: 10` | Return last N lines with a bounded rolling buffer / `show_tail/tailWrapper.pl` |
| `galaxy_tabular_to_fasta` | `title_columns: "1"`, `sequence_column: 2` | Join selected title fields with underscores and emit FASTA; skip missing columns / `tabular_to_fasta/tabular_to_fasta.py` |
| `galaxy_sam_filter_flags` | `require_flags: 0`, `exclude_flags: 0` | Require all specified bits and reject all excluded bits; omit headers / `sam_bitwise_flag_filter/sam_bitwise_flag_filter.py` |
| `galaxy_sam_to_interval` | `print_all: false` | Emit mapped alignments as zero-based half-open intervals / `sam2interval/sam2interval.py` |
| `galaxy_trim` | `column: 0`, `start: 1`, `end: 0`, `ignore_prefixes: ""`, `fastq: false` | Trim whole lines or one field / `trimmer/trimmer.py` |
| `galaxy_make_windows` | `window_size: 500`, `offset: 0`, `start_column: 2`, `end_column: 3` | Complete fixed or sliding interval windows / `windowsplitter/windowSplitter.py` |
| `galaxy_vcf_extract` | Empty reference/region/tags, `quality: 0`, `quality_operator: none`, `pass_only: false` | Select variant rows, preserve headers / `vcf_extract/extract.py` |

`columns` uses one-based selectors such as `c1,c4,c2-c3`; at most 1024 expanded columns, maximum index 999999. `delimiter` is `tab`, `comma`, `dash`, `underscore`, `pipe`, `dot` or `whitespace`. `character` additionally supports `space` and `colon`. Unknown parameters, unsupported enum values and malformed columns fail inference and execution. `value` cannot contain tabs or line breaks. Parameters are not shell commands or regular expressions. These are delimiter-based text tools, not quote-aware CSV tools; do not use them to parse quoted CSV fields.

```mermaid
flowchart LR
    A[Item bytes view] --> B[Normalize delimiters]
    B --> C[Change selected columns]
    C --> D[Append row identifier]
    D --> E[TSV bytes stream]
```

## Run a Job

After publication, run from the XStream repository root:

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job.yaml
```

The example reads `context:/galaxy/input.txt`, converts comma runs to tabs, uppercases the first column, and appends sequential labels. Its output is:

```text
SAMPLE	group	row-0
ALPHA	a	row-1
BETA	b	row-2
```

Set `remote_operator_library_urls` to your repository and configure its transport trust as needed. Publication does not require modifying core registries or rebuilding the execution server.

## Build and verify

From this directory, with the XStream JVM SDK published locally:

```bash
sbt package 'Test / runMain xstream.galaxy.SafetyCheck'
python3 make-catalog.py
python3 verify.py --docker-prefix '' --report /tmp/galaxy-tests.json
```

The verification interpreter needs PyArrow and PyYAML. `verify.py` uses the registered runtime image locally in Docker, without network access. `--docker-prefix` defaults to `sudo -n -u cnic -g docker` for this development host; use `''` when your user already has Docker access. `--runtime IMAGE` selects another compatible image. Tests compare 28 Galaxy fixture cases (two interval expectations correct the upstream padding bug), 65 execution cases including empty input, two-byte Arrow chunks, Unicode, CRLF and missing final newline; they also verify 15 inference/sample contracts, invalid parameters, invalid UTF-8, oversized lines and a three-step operator pipeline. `SafetyCheck` verifies cancellation, I/O error propagation, parameter errors, output expansion limits, FASTA record limits and tail-buffer limits. `--artifact FILE --only NAME` verifies an individual repository-built JAR before review.

The line-oriented tools in the table above read input across Arrow batches without buffering the entire Item. Lines are capped at 1,048,576 UTF-16 characters, selected/generated field joins at 4,194,304 characters, parameter strings at 4096 characters and selected columns at 1024. Working memory is bounded by these per-line limits plus SDK buffers. Output samples honor the SDK byte budget. Cancellation is checked during reading and per line; input and output failures propagate to the engine. No shell, subprocess, network or application file access is used by the business logic.

## Publish sources

```bash
python3 publish.py --url http://localhost:8820 \
  --token-file /secure/publisher.token --output /tmp/galaxy-publication
```

This submits 200 source archives for the repository's isolated Scala build and leaves them pending review. To submit one tool, append its short name, such as `cut_columns`. An administrator can add `--approve-password-file /secure/admin-password`; this mode waits for the build, downloads and verifies each candidate artifact, assigns the `Galaxy`, `Text processing` and `Tabular data` categories (or `Galaxy`, `FASTA` and `Bioinformatics` for FASTA tools, and `Galaxy`, `SAM`, `Bioinformatics` for SAM tools) and a trust score of 4, approves it, and checks that the public download matches the verified candidate. Test failure stops publication. Credentials are read from separate files and never packaged or printed. HTTPS is required for remote publication. The trust score is an assessment, not a safety guarantee. Review records and artifact test reports are written under `--output`.

Each archive contains the Scala implementation, upstream evidence, bilingual instructions, validation scripts and complete license texts; the published JAR includes `META-INF/LICENSE`, `META-INF/NOTICE` and its operator definition. Source visibility is public. See [source submission](../../../docs/operator-submission.md) for repository administration and build prerequisites.

## Behavior and attribution

The golden fixtures preserve upstream behavior. Deliberate differences: LF/CRLF/CR input is normalized to LF without dropping a final character when a trailing newline is absent; malformed UTF-8 is rejected; case conversion uses locale-independent Unicode; `condense_characters` maps whitespace runs to one space (the upstream `s` option has no valid replacement branch); dot compression emits a literal dot rather than the Perl script's escaped replacement; delimiter names are readable enumerations; malformed parameters fail instead of being silently ignored. Trailing empty fields follow the original Perl tools for change/cut and Python behavior for merge. Limits and cancellation are explicit.

Galaxy Contributors retain copyright in the original tools and fixtures. Community and IUC MIT license notices are also retained in `upstream-extended/` and the published artifact. Upstream licensing assigns pre-2024-12-01 work to AFL-3.0 and subsequent contributions to MIT. Both full texts are included unchanged in [LICENSE](LICENSE); mixed provenance is declared as `AFL-3.0 AND MIT`. See [NOTICE](NOTICE). These are independent XStream adaptations, not official Galaxy releases.

## FASTA and line selection details

`keep_first` is a Unicode character limit (zero retains the complete title); `id_only` selects the first title word. FASTA length is the count of sequence characters, not a biological quality/validation measure. For FASTA-to-tabular, `description_columns: 1` retains the full title; greater values split initial words, pad absent fields and retain the remaining description in the final title column. The sequence is emitted directly across lines without whole-sequence buffering. A sequence before a `>` header is rejected; comments and empty lines are skipped. Empty FASTA produces no entries, rather than a fictitious zero-length entry. FASTA output uses LF. Filtering retains at most one 8,388,608-character record; entries already over a nonzero maximum are discarded without retaining their sequence. As in upstream fixtures, the last retained entry has no trailing newline only if it is the final input entry. This does not flatten sequences into an inferred records schema.

Head/remove-beginning accept 0–1,000,000 lines. Tail accepts 0–10,000 lines and at most 8,388,608 retained UTF-16 characters. These three tools preserve LF, CRLF, CR and the final missing newline. They drain the input to Arrow EOS, including head when it has already emitted enough lines. They do not invoke `head`, `tail` or a shell. Length counting strips outer whitespace from sequence lines as in the original tool; filtering and tabular conversion count/preserve other sequence characters. Parameter checks occur during inference as well as processing.

Run the second complete example from the XStream root:

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-fasta.yaml
```

```text
seq1	12	sequence-0
seq3	20	sequence-1
```

Every catalog entry includes `documentation` describing all inputs, outputs and parameters, a schema-valid example value and a complete Job. The publisher sends it with the source submission; the repository validates it before accepting the submission. `catalog.json` also serves as the local reference for all 200 operator definitions. See [verification results](verification.json) for the pinned runtime and tests.

## SAM and tabular sequence examples

`job-sam.yaml` reads `data/alignments.sam`, keeps properly paired mapped reads and calculates genomic intervals. Run it with the same engine configuration as the other examples:

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-sam.yaml
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-tabular-to-fasta.yaml
```
 The expected output is:

```text
#chrom	start	end	strand	read_name
chr1	9	13	+	read1
chr1	19	27	-	read2
```

`galaxy_tabular_to_fasta` uses one-based integer column numbers (`1,2`), rather than the `c1,c2` syntax of the text-column tools. Its example reads `data/sequence-table.tsv`. A leading `>` is removed from the first selected title field; missing selected columns cause the row to be skipped, matching upstream. It does not validate the sequence alphabet.

SAM tools require at least 11 fields and a 16-bit flag. Comments and SAM headers are omitted, matching the upstream flag filter; its output is alignment rows, not a header-complete SAM document. Required and excluded masks must not overlap. The interval tool skips unmapped flags and `*` references, requires positive positions and a complete valid CIGAR, and detects coordinate overflow. `M`, `D`, `N`, `=` and `X` consume reference positions; `I`, `S`, `H` and `P` do not. This deliberately corrects the upstream script, which counted `P` and ignored `=`/`X`. Two original interval expectations are adjusted for that documented correction; separate edge tests cover extended CIGAR and padding. `print_all: true` appends the original SAM row after chromosome, start, end and strand. Input rows are limited to 1 MiB characters and output rows to 2 MiB characters; processing is streaming and cancellation-aware.

Repository-built artifact checks and successful standalone runner Jobs for the tabular/SAM tools are recorded in [stream-tool verification](verification-stream-tools.json).

## Trimming, interval windows and VCF selection

`galaxy_trim` retains characters from `start` through `end` (one-based, inclusive); `end: 0` keeps the remainder. `column: 0` trims the whole line; a positive number trims one tab-separated field. `ignore_prefixes` contains literal leading characters, not ASCII codes or regular expressions. Empty lines are skipped and line endings become LF, matching upstream. Unicode positions count characters rather than UTF-8 bytes. `fastq: true` preserves alternating identifier/separator lines in four-line FASTQ; it is not a wrapped-FASTQ parser. Invalid positions and missing selected columns fail explicitly.

`galaxy_make_windows` preserves other fields and emits zero-based half-open intervals. `offset: 0` means non-overlapping windows; positive offsets select a sliding stride. Only complete windows are emitted, including a first complete window when the stride exceeds interval length. Short intervals yield no windows. These rules correct two upstream sliding-window boundary errors. Invalid coordinates fail instead of silently dropping a row. One interval may expand to at most 100,000 windows and an estimated 64 MiB of UTF-16 character payload; every iteration checks cancellation. There is no whole-file buffering.

`galaxy_vcf_extract` supports exact reference names, inclusive one-based `region: "20:80000..100000"`, quality comparisons, INFO tag presence and PASS status. `keep_info` requires any listed exact tag; `discard_info` excludes any listed tag. The two cannot be combined. Empty criteria retain all records. Missing quality (`.`) never satisfies a numeric comparison. Headers and original record fields remain unchanged except for LF normalization. Unlike the original Python tool, it does not reorder header declarations or inject a `vcfPytools` provenance line. Five upstream VCF fixtures compare the exact selected rows plus independent preservation of input headers. The original 65-case report is complemented by 19 execution cases for these tools in `verification-trim.json`, `verification-make-windows.json` and `verification-vcf-extract.json`. `GenomicSafetyCheck` verifies malformed inputs, expansion budgets, large coordinates, exact tags, missing quality, UTF-8/I/O errors and cancellation during window expansion.

Run from the XStream repository root (with Docker access):

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-windows.yaml
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-vcf.yaml
```

The first Job reads `data/intervals.bed`, creates four-base windows every two bases, and shortens feature labels to `gene`. The second reads `data/variants.vcf` and retains PASS variants with quality at least 90 within the selected region. No external Galaxy installation or additional runtime libraries are required.

Repository artifact checks and exact runner Job output checks for trimming, windows and VCF selection are in [genomic-tool verification](verification-genomic-tools.json).

## Format plugins and bytes inputs

| Source format | Existing structured support | These operators read |
| --- | --- | --- |
| CSV / TSV | Basic delimited-records plugin | Original `bytes` |
| FASTA | `xstream-data-fasta` provides the `sequence` view | Original `bytes`, not the parsed sequence view |
| FASTQ / SAM / VCF / BED | No dedicated structured-format plugin | Generic `bytes`; each operator parses its own content |

A dedicated format plugin is not a prerequisite for these Jobs. It is useful when a catalog should expose format-specific schemas, structured views and common parsing to other operators. The format handler then owns decoding; an operator owns analysis. No file path or temporary intermediate file is required by these operator interfaces.

## FASTQ conversion

`galaxy_fastq_to_fasta@1.0` emits FASTA; `galaxy_fastq_to_qual@1.0` emits numeric QUAL bytes. Both accept only `encoding`: default `solexa64` (raw Solexa scores, no Phred conversion), `sanger33`, or `integers` (-5 through 93). No encoding guessing or offset-marker prefix is supported. Exactly four lines per record are required, with matching @/+ titles and sequence/quality counts. Lines are bounded to 1 MiB characters; only the current record is buffered. Integer separators are normalized to one space without trailing spaces. CRLF and an unterminated final line are supported; truncation, invalid UTF-8 and invalid quality values fail.

`job-fastq.yaml` reads bytes from `context:/galaxy/reads.fastq`, converts FASTQ to FASTA and emits sequence lengths as TSV without intermediate files.

Golden tests strip invalid trailing whitespace from upstream fixture `2.fastqsolexa` and normalize expected QUAL separators. Runtime FASTQ validation remains strict.

Verify and run:

```bash
cd examples/operators/galaxy
sbt package 'Test/runMain xstream.galaxy.FastqSafetyCheck'
/path/to/python-with-pyarrow verify.py --only fastq_to_fasta --report verification-fastq-to-fasta.json
/path/to/python-with-pyarrow verify.py --only fastq_to_qual --report verification-fastq-to-qual.json
cd ../../..
xstream-engine/target/universal/stage/bin/xstream-runner examples/operators/galaxy/engine.yaml examples/operators/galaxy/job-fastq.yaml
```

## Extended collection

See [Galaxy operators](../../../docs/galaxy-operators.md) for 180 additional adapters, supported scope, memory bounds, provenance and run commands. Every tool has a complete Job, input fixture and regression expectation. Reviewed sources and license notices accompany the submission.

## Composed examples

`job-fastq-quality-pipeline.yaml` converts Phred+64 to Phred+33, masks low-quality bases, tabulates reads and selects the nucleotide column. `job-interval-coverage-pipeline.yaml` sorts, merges and measures genomic intervals. Each step uses its own public operator package and the shared JVM runtime. The other four examples cover genotype normalization and dosage, edited DNA word counts, paired codon projection and adaptation, and joined numeric regression. Run `python3 verify-pipelines.py` after publication to verify all seven composed examples.

## Source packages and examples

Source packages include complete Scala source, contracts, tests, licenses and pinned upstream evidence, plus the selected operator’s generated sample. Recreate all generated inputs and Jobs with `python3 extend-catalog.py` followed by `python3 make-catalog.py` (PyYAML required). This keeps each source submission within the repository archive limits.
