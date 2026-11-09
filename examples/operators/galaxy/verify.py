#!/usr/bin/env python3
"""Verify Galaxy golden fixtures and real Arrow operator/inference protocols offline."""
import argparse,base64,hashlib,json,shlex,struct,subprocess,tempfile
from pathlib import Path
import pyarrow as pa
HERE=Path(__file__).resolve().parent
RUNTIME='sha256:23732819bf6777f314efc0454034fb8509caac9dc81b84538e52104274cb4af3'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--docker-prefix',default='sudo -n -u cnic -g docker');p.add_argument('--artifact',type=Path);p.add_argument('--runtime',default=RUNTIME);p.add_argument('--report',type=Path);p.add_argument('--only',choices=list(json.loads((HERE/'catalog.json').read_text())));a=p.parse_args()
 jar=(a.artifact or HERE/'target/scala-2.13/xstream-galaxy-operators_2.13-1.0.jar').resolve()
 specs=json.loads((HERE/'catalog.json').read_text());prefix=shlex.split(a.docker_prefix);cases=[];golden_counts={k:0 for k in specs}
 def golden(op,d,source,expected,params):
  golden_counts[op]+=1;folder=HERE/'upstream'/d/'test-data';cases.append((op,folder.joinpath(source).read_bytes(),folder.joinpath(expected).read_bytes(),params))
 golden('change_case','change_case','1.txt','changeCase_out1.tabular',{'columns':'c1','delimiter':'whitespace','casing':'upper'})
 golden('change_case','change_case','1.bed','changeCase_out2.tabular',{'columns':'c1'})
 golden('condense_characters','condense_characters','1.bed','eq-condense.dat',{'character':'tab'})
 golden('convert_delimiters','convert_characters','1.bed','eq-convert.dat',{'character':'whitespace'})
 golden('convert_delimiters','convert_characters','a.txt','a.tab',{'character':'whitespace'})
 golden('cut_columns','cut_columns','1.bed','eq-cut.dat',{'columns':'c1,c4,c2,c3'})
 golden('cut_columns','cut_columns','1.bed','eq-cut.dat',{'columns':'c1,c4,c2-c3'})
 golden('merge_columns','merge_cols','1.bed','mergeCols.dat',{'columns':'c4,c1,c6'})
 golden('add_column','add_value','1.bed','eq-addvalue.dat',{'value':'1','iterate':False})
 golden('fasta_length','fasta_compute_length','454.fasta','fasta_tool_compute_length_1.out',{'keep_first':0,'id_only':False})
 golden('fasta_length','fasta_compute_length','extract_genomic_dna_out1.fasta','fasta_tool_compute_length_2.out',{'keep_first':0})
 golden('fasta_length','fasta_compute_length','454.fasta','fasta_tool_compute_length_3.out',{'keep_first':14})
 golden('fasta_length','fasta_compute_length','merged.fa','merged.tab',{'id_only':True})
 golden('fasta_filter_length','fasta_filter_by_length','454.fasta','fasta_tool_filter_length_1.out',{'min_length':10,'max_length':0})
 golden('fasta_filter_length','fasta_filter_by_length','4.fasta','fasta_tool_filter_length_2.out',{'min_length':0,'max_length':60})
 for source,expected,params in [('454.fasta','fasta_to_tabular_out1.tabular',{}),('4.fasta','fasta_to_tabular_out2.tabular',{}),('454.fasta','fasta_to_tabular_out3.tabular',{'keep_first':14}),('454.fasta','fasta_to_tabular_out4.tabular',{'description_columns':2}),('454.fasta','fasta_to_tabular_out5.tabular',{'description_columns':5})]:
  golden('fasta_to_tabular','fasta_to_tabular',source,expected,params)
 golden('remove_beginning','remove_beginning','1.bed','eq-removebeginning.dat',{'lines':5})
 golden('head','show_beginning','1.bed','eq-showbeginning.dat',{'lines':10})
 golden('tail','show_tail','1.bed','eq-showtail.dat',{'lines':10})
 golden('tabular_to_fasta','tabular_to_fasta','solexa.tabular','tabular_to_fasta_out1.fasta',{'title_columns':'1,2,3,4','sequence_column':5})
 golden('sam_filter_flags','sam_bitwise_flag_filter','sam_bw_filter.sam','sam_bw_filter_0002-yes.sam',{'require_flags':2})
 # Original interval expectations contain a CIGAR padding bug: P does not consume the reference.
 for source,target,params in [('sam_bioinf_example.sam','sam2interval_printAll.dat',{'print_all':True}),('sam_bioinf_example.sam','sam2interval_noprintAll.dat',{}),('sam2interval-test3.sam','sam2interval_with_unmapped_reads_noprintAll.dat',{})]:
  golden('sam_to_interval','sam2interval',source,target,params)
  op,data,expected,params=cases[-1]
  expected=expected.replace(b'ref\t8\t19\t+',b'ref\t8\t18\t+')
  cases[-1]=(op,data,expected,params)
 golden('trim','trimmer','trimmer_tab_delimited.dat','trimmer_a_f_c0_s1_e13_i62.dat',{'column':0,'start':1,'end':13,'ignore_prefixes':'>'})
 golden('trim','trimmer','trimmer_tab_delimited.dat','trimmer_a_f_c2_s1_e2_i62.dat',{'column':2,'start':1,'end':2,'ignore_prefixes':'>'})
 golden('make_windows','windowsplitter','4.bed','4_windows.bed',{'window_size':5000,'offset':4000})
 for target,params in [('test_extract_pass_filter_quality_9_DP_2000_lt.vcf',{'pass_only':True}),('test_extract_region_80000_100000.vcf',{'region':'20:80000..100000'}),('test_extract_quality_90_ge.vcf',{'quality':90,'quality_operator':'ge'}),('test_extract_keep_info_TV.vcf',{'keep_info':'TV'}),('test_extract_discard_info_TV.vcf',{'discard_info':'TV'})]:
  golden('vcf_extract','vcf_extract','test_filter_quality_9_DP_2000_lt.vcf' if params.get('pass_only') else 'test.small.vcf',target,params)
  op,data,expected,params=cases[-1]
  # Preserve original headers exactly; the Python tool reorders metadata and injects its own provenance.
  headers=b''.join(line+b'\n' for line in data.splitlines() if line.startswith(b'#'))
  records=b''.join(line+b'\n' for line in expected.splitlines() if line and not line.startswith(b'#'))
  cases[-1]=(op,data,headers+records,params)
 for op in ['fastq_to_fasta','fastq_to_qual']:
  for source,stem,enc in [('1.fastqsolexa','out4','integers'),('2.fastqsolexa','out2','solexa64')]:
   golden(op,'fastqsolexa_to_fasta_qual',source,'fastqsolexa_to_fasta_qual_'+stem+('.qualsolexa' if op.endswith('qual') else '.fasta'),{'encoding':enc})
   tool,data,expected,params=cases[-1]
   # Upstream fixture 2 has trailing spaces on every physical line; use valid FASTQ.
   if source=='2.fastqsolexa':data=b'\n'.join(line.rstrip() for line in data.splitlines())+b'\n'
   # QUAL text is normalized to single separators without trailing spaces.
   if op.endswith('qual'):expected=b''.join(b' '.join(line.split())+b'\n' for line in expected.splitlines())
   cases[-1]=(tool,data,expected,params)
 cases += [
 ('change_case','éa\t好\r\nza\t世'.encode(),'ÉA\t好\nZA\t世\n'.encode(),{}),
 ('cut_columns',b'\n',b'.\t.\n',{'columns':'c1,c2'}),
 ('cut_columns',b'#skip\na\tb\nc',b'b\ta\tb\t.\n.\tc\t.\t.\n',{'columns':'c2,c1,c2,c4'}),
 ('merge_columns',b'#keep\n\na\tb',b'#keep\n\na\tb\tba\n',{'columns':'c2,c8,c1'}),
 ('condense_characters',b'a...b...\nc',b'a.b.\nc\n',{'character':'dot'}),
 ('condense_characters',b'a \t b',b'a b\n',{'character':'whitespace'}),
 ('convert_delimiters',b'  a:::b  \r\nc',b'a\tb\nc\n',{'character':'colon'}),
 ('add_column',b'a\r\nb',b'a\t8\nb\t9\n',{'value':'8','iterate':True}),
 ('add_column',b'a\nb',b'a\tid-0\nb\tid-1\n',{'value':'id','iterate':True})]
 cases += [
 ('fasta_length',b'#comment\n>one title\r\nACG\r\nTA\n>two\nNN',b'one\t5\ntwo\t2\n',{'id_only':True}),
 ('fasta_filter_length',b'>one\nACGT\n>two\nA\n',b'>one\nACGT\n',{'min_length':2,'max_length':5}),
 ('fasta_filter_length',b'>one\nACGT\n>two\nA',b'>two\nA',{'min_length':0,'max_length':2}),
 ('fasta_to_tabular',b'#comment\n>one title here\nACG\nTA',b'one\ttitle here\tACGTA\n',{'description_columns':2}),
 ('fasta_to_tabular',b'>id\nA',b'id\t\t\tA\n',{'description_columns':3}),
 ('remove_beginning',b'a\r\nb\rc',b'b\rc',{'lines':1}),
 ('head',b'a\r\nb\nc',b'a\r\nb\n',{'lines':2}),
 ('tail',b'a\r\nb\nc',b'b\nc',{'lines':2}),
 ('tail',b'a\nb',b'',{'lines':0})]
 cases += [
 ('tabular_to_fasta',b'#comment\n>id\tACGT\r\nbad\nnext\tNN',b'>id\nACGT\n>next\nNN\n',{}),
 ('tabular_to_fasta','α\tβ\tACGT'.encode(),'>α_β\nACGT\n'.encode(),{'title_columns':'1,2','sequence_column':3}),
 ('sam_filter_flags',b'@HD\tVN:1.6\nr1\t18\tchr1\t10\t20\t2=1X1P2D3N1I\t*\t0\t0\tAAAA\t*\nr2\t4\t*\t0\t0\t*\t*\t0\t0\tN\t*',b'r1\t18\tchr1\t10\t20\t2=1X1P2D3N1I\t*\t0\t0\tAAAA\t*\n',{'require_flags':2,'exclude_flags':4}),
 ('sam_to_interval',b'r1\t16\tchr1\t10\t20\t2=1X1P2D3N1I\t*\t0\t0\tAAAA\t*\nr2\t4\t*\t0\t0\t*\t*\t0\t0\tN\t*',b'#chrom\tstart\tend\tstrand\tread_name\nchr1\t9\t17\t-\tr1\n',{})]
 cases += [
 ('trim','α😀βγ\tkeep\r\n>header\n'.encode(),'😀β\tkeep\n>header\n'.encode(),{'column':1,'start':2,'end':3,'ignore_prefixes':'>'}),
 ('trim',b'@id\nACGT\n+\nIIII\n',b'@id\nCG\n+\nII\n',{'start':2,'end':3,'fastq':True}),
 ('trim',b'\na\r\nbc',b'a\nbc\n',{}),
 ('make_windows',b'chr1\t0\t3\nchr1\t5\t15\tgene',b'chr1\t5\t9\tgene\nchr1\t7\t11\tgene\nchr1\t9\t13\tgene\nchr1\t11\t15\tgene\n',{'window_size':4,'offset':2}),
 ('make_windows',b'chr1\t0\t6',b'chr1\t0\t4\n',{'window_size':4,'offset':10}),
 ('make_windows',b'chr1\t0\t10',b'chr1\t0\t4\nchr1\t4\t8\n',{'window_size':4}),
 ('vcf_extract',b'#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n20\t10\t.\tA\tG\t.\tPASS\tTV\n20\t11\t.\tA\tG\t90\tPASS\tTV=1;DP=2',b'#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n20\t11\t.\tA\tG\t90\tPASS\tTV=1;DP=2\n',{'quality':90,'quality_operator':'ge','keep_info':'TV','pass_only':True}),
 ('vcf_extract',b'20\t10\t.\tA\tG\t90\tPASS\tTV\n20\t11\t.\tA\tG\t95\t.\t.',b'20\t10\t.\tA\tG\t90\tPASS\tTV\n',{'region':'20:10..10'})]
 for op in ['fastq_to_fasta','fastq_to_qual']:
  for data,params,seq,scores in [
   (b'@r title\r\nACGT\r\n+r title\r\n!I5?\r\n',{'encoding':'sanger33'},b'ACGT',b'0 40 20 30'),
   (b'@r title\nAC\n+\n;@',{},b'AC',b'-5 0'),
   (b'@r title\nAC\n+\n-5 93',{'encoding':'integers'},b'AC',b'-5 93'),
   (b'@r title\nAC\n+\n12',{'encoding':'sanger33'},b'AC',b'16 17'),
   (b'@r title\n\n+\n\n',{},b'',b'')]:
   cases.append((op,data,b'>r title\n'+(scores if op.endswith('qual') else seq)+b'\n',params))
 for op in specs:cases.append((op,b'',b'#chrom\tstart\tend\tstrand\tread_name\n' if op=='sam_to_interval' else b'',{}))
 if a.only:
  specs={a.only:specs[a.only]};cases=[c for c in cases if c[0]==a.only]
 with tempfile.TemporaryDirectory(prefix='xstream-galaxy-verify-') as tmp:
  root=Path(tmp);root.chmod(0o755)
  def call(op,verb,d,check=True):
   command=prefix+['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','512m','--pids-limit','64','--user','65534:65534','--tmpfs','/tmp:rw,nosuid,nodev,size=32m','-v',str(jar)+':/operator/galaxy.jar:ro','-v',str(root)+':/fixtures:ro','--entrypoint','/opt/java/bin/java',a.runtime,'--add-opens=java.base/java.nio=ALL-UNNAMED','-Dio.netty.tryReflectionSetAccessible=true','-cp','/operator/galaxy.jar:/opt/lib/*',specs[op]['entrypoint'],verb]
   if verb=='operator':command += ['/fixtures/'+d.name,'/fixtures/'+d.name+'/params.json']
   else:command += ['/fixtures/'+d.name+'/request.json']
   r=subprocess.run(command,capture_output=True,timeout=60)
   if check and r.returncode:raise AssertionError(r.stderr.decode(errors='replace'))
   return r
  def fixture(index,data,params):
   d=root/str(index);d.mkdir();d.chmod(0o755)
   table=pa.table({'data':pa.array([data[i:i+2] for i in range(0,len(data),2)],type=pa.binary())})
   with (d/'input.arrow').open('wb') as f:
    with pa.ipc.new_stream(f,table.schema) as w:w.write_table(table,max_chunksize=2)
   (d/'manifest.json').write_text(json.dumps([{'port':'source','file':'input.arrow','representation':'bytes','format':'text'}]))
   (d/'params.json').write_text(json.dumps(params));return d
  def payload(raw):
   pos=0;data=bytearray();started=ended=False
   while pos<len(raw):
    hn,dn=struct.unpack_from('>ii',raw,pos);pos+=8;h=json.loads(raw[pos:pos+hn]);pos+=hn;chunk=raw[pos:pos+dn];pos+=dn
    assert h['port']=='result'
    if h['type']=='start':assert not started and h['representation']=='bytes';started=True
    elif h['type']=='data':assert started and not ended;data.extend(chunk)
    elif h['type']=='end':assert started and not ended;ended=True
    else:raise AssertionError(h)
   assert started and ended;return bytes(data)
  for i,(op,data,expected,params) in enumerate(cases):
   d=fixture(i,data,params);actual=payload(call(op,'operator',d).stdout)
   assert actual==expected,(op,i,actual[:500],expected[:500]);print('PASS execution',op,i,flush=True)
  for i,(op,s) in enumerate(specs.items()):
   d=fixture('infer-'+str(i),b'',s['params'])
   sample_data=(b'>seq example\nACGTACGTACGT\n'*20) if op.startswith('fasta_') else (b'hello\tworld\n'*200)
   if op.startswith('fastq_'):sample_data=b'@read\nACGT\n+\nIIII\n'
   if op=='make_windows':sample_data=b'chr1\t0\t20\n'
   if op=='vcf_extract':sample_data=b'#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n20\t90000\t.\tA\tG\t100\tPASS\tTV\n'
   if op.startswith('sam_'):sample_data=b'@HD\tVN:1.6\nr1\t2\tchr1\t10\t20\t4M\t*\t0\t0\tACGT\t*\n'
   sample={'representation':'bytes','format':'text','data_base64':base64.b64encode(sample_data).decode(),'truncated':False}
   request={'inputs':{'source':{'representations':['bytes'],'formats':['text'],'certainty':'known','sample':sample}},'params':s['params'],'preview':{'max_rows':10,'max_bytes':256}}
   (d/'request.json').write_text(json.dumps(request));out=json.loads(call(op,'infer',d).stdout);desc=out['outputs']['result'];assert desc['representations']==['bytes'];assert len(json.dumps(desc['sample'],separators=(',',':')).encode())<=256;print('PASS inference/preview',op,flush=True)
   bad={'columns':'c0'} if op in ['change_case','cut_columns','merge_columns'] else {'value':'a\nb'} if op=='add_column' else {'character':'(?evil)'}
   request['params']=bad;(d/'request.json').write_text(json.dumps(request));assert call(op,'infer',d,False).returncode!=0
  for index,data,params in ([('huge',b'x'*1048577,{}),('utf8',b'\xff',{})] if 'add_column' in specs else []):
   d=fixture(index,data,params);assert call('add_column','operator',d,False).returncode!=0
  for op in ['fastq_to_fasta','fastq_to_qual']:
   if op not in specs:continue
   for j,data in enumerate([b'@r\nAC\n+',b'@r\nAC\n+other\nII',b'@r\nAC\n+\nI',b'@r\nAC\n+\n III',b'@r\nAC\n+\n!I',b'@r\nAC\n+\n\xffI',b'@r\n'+b'A'*1048577+b'\n+\nI']):
    d=fixture('invalid-'+op+str(j),data,{});assert call(op,'operator',d,False).returncode!=0
  if not a.only:
   # A three-step bytes pipeline through the real IPC protocol, including a split Unicode character.
   data='éa,,,b\nxy,,z'.encode()
   for i,(op,params) in enumerate([('convert_delimiters',{'character':'comma'}),('change_case',{}),('add_column',{'value':'row','iterate':True})]):
    d=fixture('pipeline-'+str(i),data,params);data=payload(call(op,'operator',d).stdout)
   assert data=='ÉA\tb\trow-0\nXY\tz\trow-1\n'.encode()
 report={'artifact_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'runtime':a.runtime,'upstream_golden_cases':sum(golden_counts[k] for k in specs),'corrected_upstream_interval_cases':2 if 'sam_to_interval' in specs else 0,'preserved_header_vcf_fixture_cases':5 if 'vcf_extract' in specs else 0,'execution_cases':len(cases),'inference_preview_cases':len(specs),'invalid_parameter_cases':len(specs),'resource_encoding_cases':2 if 'add_column' in specs else 0,'pipeline':'passed' if not a.only else 'not requested','status':'passed'}
 if a.report:a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(report),flush=True)
if __name__=='__main__':main()
