#!/usr/bin/env python3
"""Submit Galaxy Scala sources; optional administrator review verifies each built artifact first."""
import argparse,hashlib,io,json,subprocess,sys,tempfile,time,zipfile
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.parse import urlsplit
HERE=Path(__file__).resolve().parent

def source_package(spec):
 data=io.BytesIO()
 files={'src/'+p.name:p for p in (HERE/'src/main/scala').rglob('*.scala')}
 for name in ['LICENSE','NOTICE','README.md','README_zh.md','upstream-manifest.json','verify.py','make-catalog.py','build.sbt','catalog.json','extra-specs.json','extend-catalog.py','verify-extended.py','upstream-extended-manifest.json','extended-regressions.json','upstream-dependencies-manifest.json','upstream-golden-cases.json','upstream-variant-alignment-cases.json','upstream-paired-interval-cases.json','upstream-paired-sequence-cases.json','upstream-genome-cases.json','upstream-emboss-cases.json','upstream-protein-cases.json','upstream-alignment-cases.json','upstream-numerical-cases.json','upstream-nucleotide-cases.json']:
  files[name]=HERE/name
 for p in (HERE/'upstream').rglob('*'):
  if p.is_file():files[str(p.relative_to(HERE))]=p
 for p in (HERE/'upstream-extended').rglob('*'):
  if p.is_file():files[str(p.relative_to(HERE))]=p
 for p in (HERE/'upstream-dependencies').rglob('*'):
  if p.is_file():files[str(p.relative_to(HERE))]=p
 # Generated extension fixtures/jobs are reproducible from extra-specs.json.
 # Include this operator's generated examples, avoiding one archive entry per unrelated example.
 for p in (HERE/'data').glob('*'):
  if p.is_file():files[str(p.relative_to(HERE))]=p
 short=spec['name'].removeprefix('galaxy_')
 for p in [HERE/'data/extended'/f'{short}.txt',HERE/'jobs-extended'/f'{short}.yaml',*[HERE/'data/extended'/f'{short}-{port}.txt' for port in spec.get('fixture',{}).get('inputs',{})]]:
  if p.is_file():files[str(p.relative_to(HERE))]=p
 for p in HERE.glob('*.yaml'):files[p.name]=p
 for name in ['verify-extended-jobs.py','verify-extended-pipeline.py','verify-pipelines.py','verify-upstream-reference.py','audit-publication.py','verify-variant-alignment-reference.py','verify-paired-reference.py','verify-scientific-reference.py','generate-numerical-reference.py']:files[name]=HERE/name
 for p in (HERE/'src/test').rglob('*.scala'):files['tests/'+p.name]=p
 for name in ['LICENSE','NOTICE']:files['resources/META-INF/'+name]=HERE/name
 for key in ['devteam','community','iuc']:files['resources/META-INF/GALAXY-'+key.upper()+'-LICENSE.txt']=HERE/'upstream-extended'/key/'LICENSE'
 for key,path in [('SEQTK','seqtk/LICENSE'),('SEQUENCE-UTILS','galaxy-sequence-utils/galaxy_sequence_utils-1.2.0.dist-info/LICENSE'),('BCFTOOLS','bcftools/LICENSE'),('SAMTOOLS','samtools/LICENSE')]:files['resources/META-INF/GALAXY-'+key+'-LICENSE.txt']=HERE/'upstream-dependencies'/path
 with zipfile.ZipFile(data,'w',zipfile.ZIP_DEFLATED) as z:
  assert len(files)+1<=1000,'source package exceeds repository entry limit'
  assert sum(p.stat().st_size for p in files.values())+len(json.dumps(spec))<=16777216,'expanded source package exceeds repository byte limit'
  for name,p in sorted(files.items()):
   assert p.stat().st_size<=1048576,'source file exceeds repository byte limit: '+name
   info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,p.read_bytes())
  z.writestr('resources/META-INF/galaxy-operator.json',json.dumps(spec,sort_keys=True))
 return data.getvalue()

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',required=True);p.add_argument('--token-file',required=True,type=Path);p.add_argument('--runtime',default='jvm-arrow@2.1');p.add_argument('--approve-password-file',type=Path);p.add_argument('--docker-prefix',default='sudo -n -u cnic -g docker');p.add_argument('--output',type=Path,required=True);p.add_argument('operators',nargs='*');a=p.parse_args()
 url=urlsplit(a.url);assert url.scheme=='https' or url.scheme=='http' and url.hostname in ['localhost','127.0.0.1','::1'],'remote publication requires HTTPS'
 specs=json.loads((HERE/'catalog.json').read_text());names=a.operators or list(specs)
 assert all(n in specs for n in names),'unknown operator'
 token=a.token_file.read_text().strip();cookie=csrf='';a.output.mkdir(parents=True,exist_ok=True)
 def api(path,method='GET',value=None,raw=None,admin=False,binary=False):
  headers={'Content-Type':'application/octet-stream' if raw is not None else 'application/json'}
  if admin:headers.update(Cookie=cookie,**{'X-CSRF-Token':csrf})
  else:headers['Authorization']='Bearer '+token
  body=raw if raw is not None else None if value is None else json.dumps(value).encode()
  with urlopen(Request(a.url.rstrip('/')+path,body,headers,method=method),timeout=180) as r:
   data=r.read();return data if binary else (json.loads(data),dict(r.headers))
 def store(data,filename):
  digest=hashlib.sha256(data).hexdigest();api('/v1/blobs/sha256/'+digest,'PUT',raw=data)
  return {'sha256':digest,'size':len(data),'filename':filename}
 if a.approve_password_file:
  result,headers=api('/api/admin/login','POST',{'password':a.approve_password_file.read_text().strip()});cookie=next(v for k,v in headers.items() if k.lower()=='set-cookie').split(';')[0];csrf=result['csrf']
 try:
  runtime_name,runtime_version=a.runtime.split('@');runtime=next(r for r in api('/v1/runtimes')[0] if (r['name'],r['version'])==(runtime_name,runtime_version))
  evidence=store((HERE/'LICENSE').read_bytes(),'GALAXY-LICENSE.txt');receipts=[]
  for name in names:
   s=specs[name];source=source_package(s);source_ref=store(source,s['name']+'-sources.zip')
   manifest={'api_version':2,'name':s['name'],'version':s['version'],'role':('source' if not s.get('inputs',{'source':1}) else 'transform'),'type':'sdk','description':s['description'],'icon':s['icon'],'inputs':s.get('inputs',{'source':{'representations':['bytes'],'formats':[]}}),'parameters':s['parameters'],'documentation':s['documentation'],'execution':{'executor':'jvm','entrypoint':s['entrypoint'],'config':{}},'runtime':{'image':runtime['image'],'archive':runtime['archive']},'build':{'language':'scala'},'source_public':True,'provenance':{'source_archive':source_ref,'source_repository':'https://github.com/galaxyproject/tools-devteam','source_revision':'ddbe2a1e6eded54877d786fbcfc18e594fb12932','license':'AFL-3.0 AND MIT','license_file':evidence,'build_instructions':'Scala adaptation sources are src/Galaxy.scala, src/Sequences.scala, src/Alignments.scala src/GenomicText.scala and src/Fastq.scala. Compile against the shared SDK with the registered jvm-arrow@2.1 Scala builder; include resources/ in the thin JAR. No external libraries beyond the shared runtime. README files document upstream behavior and deliberate fixes; upstream/ retains original tools and fixtures.'}}
   if 'upstream' in s:
    extended=json.loads((HERE/'upstream-extended-manifest.json').read_text());key=next(k for k,v in extended.items() if v['repository']==s['upstream']['repository']);upstream=extended[key]
    manifest['provenance'].update(source_repository=upstream['repository'],source_revision=upstream['revision'],license='AFL-3.0 AND MIT',license_file=evidence,build_instructions='Compile every src/*.scala against the shared SDK using the jvm-arrow@2.1 Scala builder. Include resources/ in the thin JAR. No external dependencies. See extra-specs.json, README and upstream-extended/ for reviewed sources, explicit supported scope and test fixtures.')
   result=api('/v1/submissions','POST',manifest)[0];sid=result['submission_id'];receipt={'operator':s['name']+'@'+s['version'],'submission_id':sid,'state':'submitted'};receipts.append(receipt);print(json.dumps(receipt),flush=True)
   (a.output/(s['name']+'-submission.json')).write_text(json.dumps(result,indent=2)+'\n')
   if not cookie:continue
   deadline=time.monotonic()+300
   while True:
    record=api('/api/admin/submissions/'+sid,admin=True)[0];state=record['build_result']['status']
    if state in ['succeeded','failed']:break
    if time.monotonic()>deadline:raise TimeoutError('build '+sid)
    time.sleep(1)
   if state!='succeeded':raise RuntimeError(record['build_result'])
   (a.output/(s['name']+'-review.json')).write_text(json.dumps(record,indent=2)+'\n')
   artifact=record['manifest']['artifact'];candidate=api('/api/admin/submissions/'+sid+'/files/'+artifact['sha256']+'/'+artifact['filename'],admin=True,binary=True)
   assert hashlib.sha256(candidate).hexdigest()==artifact['sha256']
   jar=a.output/(s['name']+'-candidate.jar');jar.write_bytes(candidate)
   subprocess.run([sys.executable,str(HERE/('verify-extended.py' if 'upstream' in s else 'verify.py')),'--artifact',str(jar),'--only',name,'--runtime',runtime['image'],'--docker-prefix',a.docker_prefix,'--report',str(a.output/(s['name']+'-test.json'))],check=True)
   api('/api/admin/submissions/'+sid+'/review','POST',{'decision':'approved','categories':s['categories'] if 'categories' in s else ['Galaxy','FASTQ','Bioinformatics'] if name.startswith('fastq_') else ['Galaxy','FASTA','Bioinformatics'] if name.startswith('fasta_') or name=='tabular_to_fasta' else ['Galaxy','SAM','Bioinformatics'] if name.startswith('sam_') else ['Galaxy','VCF','Bioinformatics'] if name=='vcf_extract' else ['Galaxy','Genomics','Intervals'] if name=='make_windows' else ['Galaxy','Text processing','Tabular data'],'trust_score':4,'note':'Reviewed Scala stream implementation against pinned upstream sources and licenses. Documented fixtures, Arrow chunked execution, inference/preview and parameter validation passed on the repository-built artifact. Business code has no network/filesystem/process invocation. UTF-8 line and output expansion budgets, cancellation and I/O propagation checked locally. Trust 4 is an administrator assessment, not a safety guarantee.'},admin=True)
   published=api('/v1/operators/'+s['name']+'/'+s['version'])[0];public=api('/v1/blobs/sha256/'+artifact['sha256']+'/'+artifact['filename'],binary=True);assert public==candidate
   (a.output/(s['name']+'-manifest.json')).write_text(json.dumps(published,indent=2)+'\n');receipt['state']='published';receipt['artifact_sha256']=artifact['sha256'];print(json.dumps(receipt),flush=True)
   (a.output/'publication.json').write_text(json.dumps(receipts,indent=2)+'\n')
 finally:
  if cookie:api('/api/admin/logout','POST',{},admin=True)
if __name__=='__main__':main()
