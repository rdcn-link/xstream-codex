# Galaxy streaming operator adapters

[中文](galaxy-operators_zh.md)

The example repository publishes 50 distinct Galaxy operators, including the 30 adapters listed below. Exact public versions and tested artifact hashes are recorded in the [catalog audit](../examples/operators/galaxy/verification-public-catalog.json). Each uses a `source` Arrow bytes input and a `result` bytes output; no filesystem path or intermediate file is required. A stream interface does not imply constant memory: whole-stream sorting and reversal use bounded buffers; biological tools may buffer one bounded record. All normalized text output uses LF and JVM Unicode lexical order where sorting is involved.

| Operator | Purpose | Supported scope |
| --- | --- | --- |
| `galaxy_reverse_lines@1.0` | Reverse the order of newline-separated text records. | Newline records only; a bounded 8 MiB-character buffer is required. No custom separators. |
| `galaxy_number_lines@1.0` | Number nonempty text lines with configurable starting number, increment and alignment. | Empty lines are unnumbered. Logical-page escape sequences are treated as literal text. |
| `galaxy_sort_lines@1.0` | Stable sort of text rows using a selected tab-separated key. | One full-field sort key; numeric mode requires finite decimal/scientific values. No locale, random or human-size sorting. Bounded 8 MiB-character row buffer. |
| `galaxy_sort_fields@1.0` | Sort the tab-separated fields within each row alphabetically. | As upstream, strips surrounding row whitespace before sorting fields. |
| `galaxy_unique_lines@1.0` | Sort text lines and retain one occurrence of each comparison key. | Whole line or one selected field; stable representative, bounded 8 MiB-character buffer. Header lines pass unchanged. |
| `galaxy_unique_consecutive@1.0` | Collapse adjacent equal text records, optionally counting or selecting duplicate groups. | Only consecutive duplicates are grouped; no arbitrary field skipping or blank-delimited grouping. |
| `galaxy_unfold_column@1.0` | Expand a delimited list in one tab-separated field into multiple rows. | Literal nonempty separator; at most 10000 output rows and 16 MiB estimated characters per input row. Fixes upstream last-column newline handling. |
| `galaxy_replace_text_in_column@1.0` | Replace literal text within one tab-separated field. | Literal matching only: no regular expressions, shell commands, sed programs or replacement backreferences. Case-insensitive matching is locale-independent. Missing selected columns fail. |
| `galaxy_replace_text_in_line@1.0` | Replace literal text within complete lines. | Literal matching only: no regular expressions, shell commands, sed programs or replacement backreferences. Case-insensitive matching is locale-independent. Missing selected columns fail. |
| `galaxy_find_and_replace@1.0` | Replace literal text within complete lines. | Literal matching only: no regular expressions, shell commands, sed programs or replacement backreferences. Case-insensitive matching is locale-independent. Missing selected columns fail. |
| `galaxy_grep_lines@1.0` | Select lines containing a literal search string, with optional inversion and case folding. | Literal text search; no regex, context-line expansion or HTML output. |
| `galaxy_arrange_columns@1.0` | Reorder table columns by their header names, optionally discarding unselected columns. | Unique header names and requested names are required. Ragged rows and unknown headers fail. |
| `galaxy_add_line@1.0` | Add a literal header or footer line to a text stream. | Existing data lines are normalized to LF; this avoids concatenating a footer onto an unterminated final row. |
| `galaxy_recurring_lines@1.0` | Generate a configured line repeatedly after draining the input stream. | One explicit repetition group, bounded 16 MiB output expansion; input is drained, not used to infer repetitions. |
| `galaxy_remove_columns_by_header@1.0` | Remove named table columns or keep only the named columns. | Tab-delimited UTF-8; optional leading-character stripping on first header field. Retains source column order. Unknown requested headers fail. |
| `galaxy_sort_columns_by_header@1.0` | Alphabetically reorder table columns by header, optionally pinning one column first. | Zero sorts every column; otherwise a one-based column stays first. Headers must be unique. |
| `galaxy_add_input_name@1.0` | Add an input label as a new table column, with optional header and prepending. | The input label is explicit configuration; no local path or filename is required. |
| `galaxy_fixed_width_to_tabular@1.0` | Convert explicitly configured fixed-width fields into trimmed tab-separated columns. | Explicit character widths only; no automatic boundary detection, FORTRAN descriptors, reports or encoding conversion. Long rows or unpadded short rows fail. |
| `galaxy_colorspace_to_fasta@1.0` | Convert FASTA-like SOLiD color-space sequences to nucleotide FASTA. | A/C/G/T primer followed only by 0..3; wrapped sequence lines accepted. Buffers at most one 8 MiB-character sequence. |
| `galaxy_split_paired_reads@1.0` | Extract one mate from concatenated equal-length paired FASTQ records. | Select mate 1 or 2; validates four-line FASTQ, optional matching + title, equal sequence/quality lengths and even sequence length. No silent truncation. An empty + title remains empty so emitted FASTQ remains valid. |
| `galaxy_pileup_interval@1.0` | Collapse consecutive pileup positions meeting a minimum depth into BED-like sequence intervals. | One-based pileup to zero-based half-open intervals. Configurable columns cover six-column, legacy ten-column and custom rows. Active interval limited to 8 MiB bases; blank lines are skipped rather than terminating all input. |
| `galaxy_divide_pg_snp@1.0` | Expand one or two pgSnp alleles, frequencies and scores into separate columns. | Rows with more than two alleles are skipped. Homozygous second allele is N or the configured reference column; extra input columns are retained. |
| `galaxy_vcf_to_pgsnp@1.0` | Convert called VCF SNP genotypes to pgSnp allele counts. | Zero selects all samples; otherwise use a one-based VCF column >=10. Only single-base A/C/G/T SNPs with PASS or missing FILTER are converted. GT position, haploidy, phased calls and all allele indices are handled; missing alleles ignored. |
| `galaxy_reverse_complement@1.0` | Reverse-complement FASTA nucleotide sequences using the IUPAC alphabet. | FASTA input/output only; DNA or RNA IUPAC symbols, case preserved, invalid characters rejected. One sequence buffered up to 8 MiB characters. |
| `galaxy_translate@1.0` | Translate nucleotide FASTA to protein FASTA with the standard genetic code. | Standard code table 1, FASTA only. Ambiguous complete codons emit X; terminal partial codons fail. CDS requires ATG start, terminal stop and no internal stop; final stop omitted. Alternate genetic codes are not advertised. |
| `galaxy_codon_count@1.0` | Count all 64 unambiguous DNA codons over FASTA sequences in frame zero. | Input lengths must be multiples of three. Only A/C/G/T codons are accepted; output is deterministic lexicographic codon/count TSV, including zero counts. |
| `galaxy_base_distribution@1.0` | Report per-sequence A/C/G/T counts, sequence length and GC fraction. | FASTA input. GC denominator includes ambiguous symbols; zero-length records use GC fraction zero. Output CG% is the upstream fraction, not a percentage. |
| `galaxy_find_subsequences@1.0` | Find IUPAC nucleotide motifs in FASTA and emit zero-based half-open BED6 locations. | Uses reverse complement for reverse-strand search, correcting the upstream complement-only bug. Overlapping hits are consistently included. Maximum one million candidate comparisons per record times pattern length is bounded to 64 million. |
| `galaxy_fasta_stats@1.0` | Compute scaffold, contig and base-composition summary statistics from FASTA. | Statistics TSV output only, no separate gap BED. N-runs split contigs; internal N-runs count as gaps. Uses standard N50 threshold >= half, correcting upstream strict-greater edge. At most 100000 sequences/contigs and one 8 MiB-character record. |
| `galaxy_motif_flank_counts@1.0` | Count motif-bearing windows upstream and downstream of each indel. | Input has an odd number of tab-separated windows with literal indel in the middle. Motifs are literal case-insensitive DNA strings configured by name=sequence or sequence. Output is row-major then motif order to stream without replaying input; upstream uses motif-major order. Regex motifs are not supported. |

## Develop, verify and run

```bash
cd examples/operators/galaxy
python3 extend-catalog.py
python3 make-catalog.py
sbt package 'Test/runMain xstream.galaxy.ExtendedSafetyCheck'
python3 verify-extended.py --only reverse_complement --report verification-reverse-complement.json
cd ../../..
xstream-engine/target/universal/stage/bin/xstream-runner examples/operators/galaxy/engine.yaml examples/operators/galaxy/jobs-extended/reverse_complement.yaml
```

Use Python with PyArrow and PyYAML installed and Docker access to the configured shared JVM image. Every `jobs-extended/<name>.yaml` has a corresponding `data/extended/<name>.txt`. The full example includes input loading; the repository detail page presents only the current operator step. Parameters, defaults, complete examples and exact regression expectations are in `extra-specs.json`. `extend-catalog.py` regenerates contracts, fixtures and example Jobs. Zero-copy or fully streaming behavior is not claimed for global sorting/reversal or per-record sequence processing.

## Provenance and review

Original reviewed sources, XML definitions, fixtures and license notices are retained under `upstream-extended/{devteam,community,iuc}/`. `upstream-extended-manifest.json` records repository URLs, pinned revisions and SHA-256 hashes. Adaptations deliberately expose a documented subset where upstream features require executable expressions, external programs, replay or additional files. Unsupported options are rejected; these packages are not drop-in replacements for every upstream command line. Source-only repository publication builds a thin Scala JAR against `jvm-arrow@2.1`, tests that artifact using chunked Arrow IPC plus inference/preview, then approves it. The administrator trust score is an assessment, not a safety guarantee.

## Compose and audit published tools

The [sequence pipeline](../examples/operators/galaxy/job-extended-pipeline.yaml) decodes SOLiD colors, computes a reverse complement, measures the sequence and appends an explicit input label. Its output is `seq1\t4\trun1\n`.

```mermaid
flowchart LR
    I[Item bytes: A013] --> D[Color-space decode: AACG]
    D --> R[Reverse complement: CGTT]
    R --> L[FASTA length: 4]
    L --> N[Append label: run1]
    N --> O[TSV bytes stream]
```

Run from the XStream repository root after publication:

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/galaxy/engine-extended.yaml \
  examples/operators/galaxy/job-extended-pipeline.yaml
python3 examples/operators/galaxy/verify-extended-jobs.py
python3 examples/operators/galaxy/audit-publication.py --url http://127.0.0.1:8820
```

[Public catalog audit](../examples/operators/galaxy/verification-public-catalog.json) checks fifty distinct names, exact versions, complete port/parameter documentation, usage examples, anonymously downloadable source/artifact hashes, entrypoint classes and embedded license notices. The recorded audit also matches every artifact to its passed repository-build test report. [Extended Job checks](../examples/operators/galaxy/verification-extended-jobs.json) compare each of the thirty additional Jobs with independently specified fixture output. [Pipeline verification](../examples/operators/galaxy/verification-extended-pipeline.json) covers composition with the original FASTA length adapter. The [safety report](../examples/operators/galaxy/verification-safety.json) records four passing safety suites; `ExtendedSafetyCheck` exercises 249 cases, including cancellation, I/O failures, invalid UTF-8, line limits, upstream golden fixtures and malformed biological input. Administrator trust scores do not replace these supported-scope and resource limits.
