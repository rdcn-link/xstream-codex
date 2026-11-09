#!/usr/bin/env python3
"""Generate the source-only operator definitions; no credentials are read."""
import json
import yaml
from pathlib import Path
HERE=Path(__file__).resolve().parent
columns={'type':'string','default':'c1','maxLength':4096,'description':'One-based columns, for example c1,c3 or c2-c4. At most 1024 selected columns.'}
delimiter={'type':'string','enum':['tab','comma','dash','underscore','pipe','dot','whitespace'],'default':'tab'}
characters=['tab','space','whitespace','dot','comma','dash','underscore','pipe','colon']
specs={
'change_case':('ChangeCase','Change selected columns to upper or lower case and emit tab-separated text.',{'columns':columns,'delimiter':delimiter,'casing':{'type':'string','enum':['upper','lower'],'default':'upper'}},{'columns':'c1','delimiter':'tab','casing':'upper'},'M3 18 8 6l5 12M5 14h6m5-3h5l-5 7h5'),
'condense_characters':('CondenseCharacters','Replace runs of a selected delimiter with one character, preserving the other text.',{'character':{'type':'string','enum':characters,'default':'tab'}},{'character':'tab'},'M3 6h18M3 18h18m-15-6h12m-9-3-3 3 3 3m6-6 3 3-3 3'),
'convert_delimiters':('ConvertDelimiters','Trim each text line and convert runs of a selected delimiter to tabs.',{'character':{'type':'string','enum':characters,'default':'tab'}},{'character':'whitespace'},'M3 7h7m4 0h7M3 17h7m4 0h7M7 10l5 4 5-4'),
'cut_columns':('CutColumns','Select and reorder text columns, supporting ranges and repeated columns; fill missing values with a dot.',{'columns':columns,'delimiter':delimiter},{'columns':'c1,c4,c2-c3','delimiter':'tab'},'M3 4h18v16H3zM8 4v16m8-16v16M3 9h18'),
'merge_columns':('MergeColumns','Append a column formed by concatenating the selected tab-separated fields; preserve comment and empty lines.',{'columns':columns},{'columns':'c4,c1,c6'},'M4 4v7l8 5 8-5V4M12 16v5M1 7h6m10 0h6'),
'add_column':('AddColumn','Append a fixed value or sequential identifier to every line of tabular text.',{'value':{'type':'string','default':'1','maxLength':4096},'iterate':{'type':'boolean','default':False}},{'value':'1','iterate':False},'M3 4h12v16H3zM8 4v16m10-11v6m-3-3h6')}
def integer(default=0,maximum=1000000000):return {'type':'integer','minimum':0,'maximum':maximum,'default':default}
keep=integer(0,4096)
specs.update({
 'fasta_length':('FastaLength','Count sequence characters per FASTA entry and emit title/identifier and length as TSV.',{'keep_first':keep,'id_only':{'type':'boolean','default':False}},{'keep_first':0,'id_only':True},'M4 3h16v18H4zM8 7h8M8 12h4m-4 5h8'),
 'fasta_filter_length':('FastaFilterLength','Keep FASTA entries whose sequence lengths fall within the inclusive configured interval.',{'min_length':integer(),'max_length':integer()},{'min_length':10,'max_length':0},'M3 4h18l-7 8v7l-4 2v-9z'),
 'fasta_to_tabular':('FastaToTabular','Convert FASTA entries into title columns followed by their concatenated sequence.',{'keep_first':keep,'description_columns':{'type':'integer','minimum':1,'maximum':32,'default':1}},{'keep_first':0,'description_columns':2},'M3 4h18v16H3zM3 10h18m-12 0v10m6-10v10'),
 'remove_beginning':('RemoveBeginning','Discard the first N text lines and stream the remaining lines unchanged.',{'lines':integer(10,1000000)},{'lines':2},'M4 5h16M4 10h16M4 15h16M4 20h16M2 2l20 10'),
 'head':('Head','Return the first N text lines, preserving their line endings and draining the input.',{'lines':integer(10,1000000)},{'lines':2},'M4 5h16M4 10h16m-16 5h10m-10 5h6M18 13v8m-3-5 3-3 3 3'),
 'tail':('Tail','Return the last N text lines with a bounded rolling buffer, preserving original line endings.',{'lines':integer(10,10000)},{'lines':2},'M4 5h6M4 10h10M4 15h16M4 20h16M18 3v8m-3-3 3 3 3-3')})
specs.update({
 'tabular_to_fasta':('TabularToFasta','Convert tab-separated title and sequence columns to FASTA entries; skip rows missing selected fields.',{'title_columns':{'type':'string','default':'1','maxLength':4096},'sequence_column':{'type':'integer','minimum':1,'maximum':65536,'default':2}},{'title_columns':'1','sequence_column':2},'M3 4h8v16H3zM15 5h6m-6 7h6m-6 7h6m-9-9 3 2-3 2'),
 'sam_filter_flags':('SamFilterFlags','Select SAM alignments by required and excluded bit masks; omit headers and comments.',{'require_flags':integer(0,65535),'exclude_flags':integer(0,65535)},{'require_flags':2,'exclude_flags':4},'M3 4h18l-7 8v7l-4 2v-9zM16 15h5m-5 4h5'),
 'sam_to_interval':('SamToInterval','Convert mapped SAM alignments to zero-based half-open genomic intervals using reference-consuming CIGAR operations.',{'print_all':{'type':'boolean','default':False}},{'print_all':False},'M3 6h18M3 18h18M6 3v6m12 6v6M6 12h12')})
specs.update({
 'trim':('Trim','Trim whole text lines or one tab-separated column using one-based inclusive character positions.',{'column':integer(0,65536),'start':{'type':'integer','minimum':1,'maximum':1048576,'default':1},'end':integer(0,1048576),'ignore_prefixes':{'type':'string','maxLength':4096,'default':''},'fastq':{'type':'boolean','default':False}},{'column':0,'start':2,'end':5,'ignore_prefixes':'>','fastq':False},'M4 4v16M20 4v16M7 8h10M7 16h10M9 5l-3 7 3 7m6-14 3 7-3 7'),
 'make_windows':('MakeWindows','Split genomic intervals into complete fixed-size or sliding windows, preserving other columns.',{'window_size':{'type':'integer','minimum':1,'maximum':1000000000,'default':500},'offset':integer(),'start_column':{'type':'integer','minimum':1,'maximum':65536,'default':2},'end_column':{'type':'integer','minimum':1,'maximum':65536,'default':3}},{'window_size':4,'offset':2,'start_column':2,'end_column':3},'M3 5h18v14H3zM8 5v14m5-14v14m5-14v14'),
 'vcf_extract':('VcfExtract','Select VCF variants by reference, inclusive region, quality, INFO tags and PASS status while preserving headers.',{'reference_sequence':{'type':'string','maxLength':4096,'default':''},'region':{'type':'string','maxLength':4096,'default':''},'quality':{'type':'number','minimum':0,'default':0},'quality_operator':{'type':'string','enum':['none','eq','le','lt','ge','gt'],'default':'none'},'keep_info':{'type':'string','maxLength':4096,'default':''},'discard_info':{'type':'string','maxLength':4096,'default':''},'pass_only':{'type':'boolean','default':False}},{'reference_sequence':'','region':'20:80000..100000','quality':90,'quality_operator':'ge','keep_info':'','discard_info':'','pass_only':True},'M4 3h12l4 4v14H4zM8 11h8M8 15h5m-5 4h8')})
for name,cls,output in [('fastq_to_fasta','FastqToFasta','FASTA sequences'),('fastq_to_qual','FastqToQual','QUAL numeric scores')]:
 specs[name]=(cls,'Convert four-line FASTQ bytes to '+output+' using an explicit quality encoding.',{'encoding':{'type':'string','enum':['solexa64','sanger33','integers'],'default':'solexa64'}},{'encoding':'sanger33'},'M4 3h16v18H4zM8 7h8m-8 5h8m-8 5h5')
FIELD_INFO={
 'encoding':('Quality encoding of the input FASTQ.','solexa64 preserves raw Solexa scores (ASCII minus 64, minimum -5); sanger33 uses Phred ASCII minus 33; integers accepts space-separated scores from -5 to 93. Exactly four lines per record; quality count must equal sequence length. No prefix-marker autodetection.'),
 'column':('Column to trim, or zero for the entire line.','Use a one-based tab-separated column number; zero trims the complete line. Missing columns fail.'),
 'start':('First retained character position.','One-based inclusive Unicode character position; one keeps the beginning.'),
 'end':('Last retained character position.','One-based inclusive end; zero keeps the rest of the value. A nonzero end must be at least start.'),
 'ignore_prefixes':('Leading characters whose lines remain unchanged.','A string of literal Unicode characters, for example >@+. Each character is a separate ignored prefix, not a regular expression.'),
 'fastq':('Whether to preserve alternating FASTQ identifier lines.','For four-line FASTQ, preserves physical lines 1 and 3 and trims sequence and quality lines. Wrapped FASTQ is not supported.'),
 'window_size':('Window length in coordinate units.','Positive integer. Only complete zero-based half-open windows are emitted.'),
 'offset':('Sliding-window stride.','Zero uses consecutive non-overlapping windows. A positive offset advances each next window by that amount.'),
 'start_column':('One-based interval start column.','Default 2 for BED. Must differ from end_column.'),
 'end_column':('One-based interval end column.','Default 3 for BED. Coordinates must be ordered and nonnegative.'),
 'reference_sequence':('Reference sequence name to retain.','Exact VCF CHROM value, for example 20. Leave empty if using region.'),
 'region':('Inclusive one-based VCF position range.','Use reference:start..end, for example 20:80000..100000. Cannot be combined with reference_sequence.'),
 'quality':('Quality comparison threshold.','Finite nonnegative number. Applied only when quality_operator is not none; missing QUAL does not satisfy a comparison.'),
 'quality_operator':('Comparison applied to VCF QUAL.','none disables quality filtering; eq, le, lt, ge and gt compare QUAL with quality.'),
 'keep_info':('INFO tags of which at least one must be present.','Comma-separated exact tag names such as TV,DP. Flags and key=value entries both count. Cannot be combined with discard_info.'),
 'discard_info':('INFO tags that must all be absent.','Comma-separated exact tag names. Cannot be combined with keep_info.'),
 'pass_only':('Whether FILTER must equal PASS.','true excludes filtered rows and rows with missing FILTER; false applies no FILTER restriction.'),
 'title_columns':('One-based tab-separated title columns.','Use comma-separated positive column numbers, for example 1,2. Up to 128 columns; their values are joined with underscores.'),
 'sequence_column':('One-based sequence column.','Choose an existing tab-separated column. Missing selected columns cause that row to be skipped.'),
 'require_flags':('SAM flags that must all be set.','Integer bit mask, for example 2 for properly paired reads. Must not overlap exclude_flags.'),
 'exclude_flags':('SAM flags that must all be unset.','Integer bit mask, for example 4 for unmapped reads or 1024 for duplicates. SAM flags are 16-bit integers.'),
 'print_all':('Whether to retain original SAM fields after interval coordinates.','false emits chromosome, start, end, strand and read name. true appends the complete original SAM row after strand. CIGAR M/D/N/=/X consume reference bases; I/S/H/P do not.'),
 'columns':('One-based field selectors.','Use c1,c3 or c2-c4; at most 1024 expanded columns. This is delimiter-based text, not quote-aware CSV.'),
 'delimiter':('Input field delimiter.','Choose one of the listed delimiter names. The output is tab-separated.'),
 'casing':('Case conversion applied to the selected fields.','upper converts to uppercase; lower converts to lowercase, using locale-independent Unicode rules.'),
 'character':('Delimiter character to process.','Choose a listed delimiter name. whitespace means any whitespace; tab matches only tab characters.'),
 'value':('Fixed value or starting sequence label.','Use a nonnegative integer for numeric iteration or text for labels. Tabs and line breaks are forbidden.'),
 'iterate':('Whether the appended field increments for each row.','false repeats value; true emits increasing numbers or labels such as sample-0.'),
 'keep_first':('Maximum retained title/identifier characters.','Zero keeps the complete title or identifier; positive values truncate it to that many Unicode characters.'),
 'id_only':('Whether to discard the description following a FASTA identifier.','true retains the first title word; false retains the complete title before optional truncation.'),
 'min_length':('Minimum accepted sequence length.','Inclusive lower bound, in sequence characters. FASTA headers and line endings do not count.'),
 'max_length':('Maximum accepted sequence length.','Inclusive upper bound; zero removes the upper threshold. A retained FASTA record is still limited to 8 MiB characters.'),
 'description_columns':('Number of columns extracted from the FASTA title.','One keeps the whole title. Larger values split the first words; the last title column retains the remaining description.'),
 'lines':('Number of lines to select or discard.','Use a nonnegative integer. Tail is limited to 10000 lines and an 8 MiB-character rolling buffer.')}

result={}
for name,(cls,description,properties,params,path) in specs.items():
 result[name]={'name':'galaxy_'+name,'version':'1.0.1' if name=='tabular_to_fasta' else '1.0','description':description,'entrypoint':'xstream.galaxy.'+cls,'icon':{'path':path,'color':'#5265ad'},'parameters':{'type':'object','properties':properties,'required':[],'additionalProperties':False},'params':params}
for name,item in result.items():
 pdocs={}
 for key,schema in item['parameters']['properties'].items():
  desc,help=FIELD_INFO[key];schema['description']=desc
  pdocs[key]={'description':desc,'help':help,'example':item['params'][key] if key in item['params'] else schema['default']}
 source='reads.fastq' if name.startswith('fastq_') else 'variants.vcf' if name=='vcf_extract' else 'intervals.bed' if name=='make_windows' else 'sequence-table.tsv' if name=='tabular_to_fasta' else 'alignments.sam' if name.startswith('sam_') else 'sequences.fasta' if name.startswith('fasta_') else 'input.txt' if name=='convert_delimiters' else 'table.tsv'
 params=dict(item['params'])
 if name=='convert_delimiters':params['character']='comma';pdocs['character']['example']='comma'
 job={'name':item['description'],'steps':[{'id':'read','operator':'read_item@1.0','inputs':{},'params':{'uri':'context:/galaxy/'+source,'view':'bytes'}},{'id':'process','operator':item['name']+'@'+item['version'],'inputs':{'source':'read'},'params':params}],'outputs':{'result':'process'}}
 item['documentation']={'inputs':{'source':'Four-line FASTQ bytes with matching @/+ titles and explicit quality encoding; at most 1 MiB characters per line.' if name.startswith('fastq_') else 'UTF-8 VCF bytes with at least eight tab-separated fields per record. Header lines are preserved.' if name=='vcf_extract' else 'UTF-8 BED/interval bytes. Coordinates are zero-based half-open; all other columns are preserved.' if name=='make_windows' else 'UTF-8 SAM bytes, including optional @ headers. Alignments require at least 11 fields.' if name.startswith('sam_') else 'UTF-8 FASTA bytes with > headers and one or more sequence lines.' if name.startswith('fasta_') else 'UTF-8 text bytes. Column tools use the selected delimiter, without CSV quote interpretation.'},'outputs':{'result':item['description']},'parameters':pdocs,'example_job':yaml.safe_dump(job,sort_keys=False,allow_unicode=True)}
result.update(json.loads((HERE/'extra-specs.json').read_text()))
(HERE/'catalog.json').write_text(json.dumps(result,indent=2)+'\n')
