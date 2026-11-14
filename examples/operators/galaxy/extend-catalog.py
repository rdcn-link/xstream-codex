#!/usr/bin/env python3
"""Generate the reviewed adapters' contracts, examples and runnable input fixtures."""
import json,yaml
from pathlib import Path
ROOT=Path(__file__).resolve().parent
specs=json.loads((ROOT/'extra-specs.json').read_text())
DESCRIPTIONS={
'start':'First line number.','increment':'Positive increment between numbered lines.','width':'Minimum number-label width.','style':'Line-number alignment: ln left, rn right or rz zero-filled.',
'column':'One-based selected field, or zero for a whole-line key where allowed.','numeric':'Compare keys as finite decimal/scientific numbers.','reverse':'Use descending key order.','ignore_case':'Compare without case differences.','header':'Header line count, or new header label for label-column operators.',
'count':'Prefix each emitted group by its occurrence count.','mode':'Emit every group, repeated groups, or singleton groups.','separator':'Literal separator between values in the selected field.',
'find':'Nonempty literal search text.','replacement':'Literal replacement text.','first_only':'Replace only the first match per row or selected field.','skip_header':'Keep the first input row unchanged.',
'pattern':'Literal text or IUPAC DNA motif, as described by this operator.','invert':'Keep lines that do not contain the pattern.',
'names':'Unique header names to select, remove or reorder.','discard':'Discard columns not explicitly selected.','text':'Literal line content.','position':'Insert the line as a header or footer.','times':'Number of generated repetitions.',
'keep':'Keep only the named columns instead of removing them.','strip_prefix':'Characters stripped from the beginning of the first header name when matching.',
'key_column':'One-based column to keep first, or zero to sort every header.','label':'Explicit input name to append or prepend.','prepend':'Place the added label column first.','widths':'Positive fixed-width character counts for each field.','pad_short':'Pad short fixed-width rows with spaces.',
'keep_prefix':'Include the SOLiD primer nucleotide in the output.','mate':'One-based mate number, 1 or 2.','min_coverage':'Inclusive minimum accepted pileup depth.',
'sequence_column':'One-based chromosome/sequence-name column.','position_column':'One-based input position column.','base_column':'One-based reference-base column.','coverage_column':'One-based pileup coverage column.',
'reference_column':'One-based reference-allele column, or zero for N.','sample_column':'One-based VCF sample column, or zero for all samples.','prefix_chr':'Add chr to sequence names without that prefix.',
'alphabet':'DNA or RNA alphabet for complementing.','to_stop':'Stop translation before the first stop codon.','cds':'Validate a complete standard-code CDS and omit its terminal stop.',
'strand':'Search forward, reverse-complement, or both strands.','genome_size':'Estimated genome size for NG50, or zero to omit NG50.','motifs':'Literal DNA motifs, each written as sequence or name=sequence.','windows':'Number of adjacent windows considered on each side of the indel.'}
sequence={'reverse_complement','translate','codon_count','base_distribution','find_subsequences','fasta_stats','colorspace_to_fasta'}
genomic={'split_paired_reads','pileup_interval','divide_pg_snp','vcf_to_pgsnp','motif_flank_counts'}
for name,s in specs.items():
 docs={}
 for key,prop in s['parameters']['properties'].items():
  desc=prop.get("description",DESCRIPTIONS.get(key,key.replace("_"," ").capitalize()+"."));prop['description']=desc
  docs[key]={'description':desc,'help':desc+' '+s['scope'],'example':s['params'].get(key,prop['default'])}
 source='context:/galaxy/extended/'+name+'.txt'
 job={'name':s['description'],'steps':[{'id':'read','operator':'item_source@1.0','inputs':{},'params':{'uri':source,'view':'bytes'}},{'id':'process','operator':s['name']+'@1.0','inputs':{'source':'read'},'params':s['params']}],'outputs':{'result':'process'}}
 extra_inputs=s['fixture'].get('inputs',{})
 for port,value in extra_inputs.items():
  read_id='read_'+port
  job['steps'].insert(1,{'id':read_id,'operator':'item_source@1.0','inputs':{},'params':{'uri':'context:/galaxy/extended/'+name+'-'+port+'.txt','view':'bytes'}})
  job['steps'][-1]['inputs'][port]=read_id
  (ROOT/'data/extended'/f'{name}-{port}.txt').write_text(value)
 s['inputs']={port:{'representations':['bytes'],'formats':[]} for port in ['source',*extra_inputs]}
 s['documentation']={'inputs':{port:'UTF-8 bytes stream. '+s['scope'] for port in s['inputs']},'outputs':{'result':s['description']+' '+s['scope']},'parameters':docs,'example_job':yaml.safe_dump(job,sort_keys=False,allow_unicode=True)}
 s['icon']={'path':'M4 3h12l4 4v14H4zM8 11h8M8 15h8m-8 4h5','color':'#5265ad'}
 data=ROOT/'data/extended'/f'{name}.txt';data.parent.mkdir(parents=True,exist_ok=True);data.write_text(s['fixture']['data'])
 jobpath=ROOT/'jobs-extended'/f'{name}.yaml';jobpath.parent.mkdir(parents=True,exist_ok=True);jobpath.write_text(s['documentation']['example_job'])
(ROOT/'extra-specs.json').write_text(json.dumps(specs,indent=2)+'\n')
classes='\n'.join('object '+v['entrypoint'].split('.')[-1]+' extends '+v.get('implementation',('ExtendedSequence' if k in sequence else 'ExtendedGenomic' if k in genomic else 'ExtendedText'))+'("'+k+'")' for k,v in specs.items())
schemas=[json.dumps({k:{'parameters':s['parameters']} for k,s in list(specs.items())[i:i+15]},separators=(',',':')) for i in range(0,len(specs),15)]
registry='package xstream.galaxy\n// Generated by extend-catalog.py.\nobject ExtendedContract { val specs = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); private val mapper=new com.fasterxml.jackson.databind.ObjectMapper();\n'+''.join('specs.setAll[com.fasterxml.jackson.databind.JsonNode](mapper.readTree("""'+chunk+'""").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]);\n' for chunk in schemas)+'}\n'
(ROOT/'src/main/scala/xstream/galaxy/ExtendedContract.scala').write_text(registry+classes+'\n')
