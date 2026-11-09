#!/usr/bin/env python3
"""Verify an extended Galaxy operator against its repository-built JAR over real Arrow IPC."""
import argparse,base64,hashlib,json,shlex,struct,subprocess,tempfile
from pathlib import Path
import pyarrow as pa
HERE=Path(__file__).resolve().parent
RUNTIME='sha256:23732819bf6777f314efc0454034fb8509caac9dc81b84538e52104274cb4af3'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--only',required=True);p.add_argument('--artifact',type=Path);p.add_argument('--runtime',default=RUNTIME);p.add_argument('--docker-prefix',default='sudo -n -u cnic -g docker');p.add_argument('--report',type=Path);args=p.parse_args()
 specs=json.loads((HERE/'extra-specs.json').read_text());s=specs[args.only];f=s['fixture'];jar=(args.artifact or HERE/'target/scala-2.13/xstream-galaxy-operators_2.13-1.0.jar').resolve();prefix=shlex.split(args.docker_prefix)
 with tempfile.TemporaryDirectory(prefix='galaxy-extended-arrow-') as temp:
  root=Path(temp);root.chmod(0o755)
  def fixture(name,data,params):
   d=root/name;d.mkdir();d.chmod(0o755)
   table=pa.table({'data':pa.array([data[i:i+2] for i in range(0,len(data),2)],type=pa.binary())})
   with (d/'input.arrow').open('wb') as stream:
    with pa.ipc.new_stream(stream,table.schema) as writer:writer.write_table(table,max_chunksize=2)
   (d/'manifest.json').write_text(json.dumps([{'port':'source','file':'input.arrow','representation':'bytes','format':'text'}]));(d/'params.json').write_text(json.dumps(params));return d
  def call(d,verb='operator',ok=True):
   cmd=prefix+['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','512m','--pids-limit','64','--user','65534:65534','--tmpfs','/tmp:rw,nosuid,nodev,size=32m','-v',str(jar)+':/operator/galaxy.jar:ro','-v',str(root)+':/fixtures:ro','--entrypoint','/opt/java/bin/java',args.runtime,'--add-opens=java.base/java.nio=ALL-UNNAMED','-Dio.netty.tryReflectionSetAccessible=true','-cp','/operator/galaxy.jar:/opt/lib/*',s['entrypoint'],verb]
   cmd+=['/fixtures/'+d.name,'/fixtures/'+d.name+'/params.json'] if verb=='operator' else ['/fixtures/'+d.name+'/request.json']
   r=subprocess.run(cmd,capture_output=True,timeout=90)
   if ok:assert r.returncode==0,r.stderr.decode(errors='replace')
   else:assert r.returncode!=0,'invalid request accepted'
   return r.stdout
  def payload(raw):
   out=bytearray();pos=0;started=ended=False
   while pos<len(raw):
    assert len(raw)-pos>=8;hn,dn=struct.unpack_from('>ii',raw,pos);pos+=8;assert hn>0 and dn>=0 and hn+dn<=len(raw)-pos
    h=json.loads(raw[pos:pos+hn]);pos+=hn;chunk=raw[pos:pos+dn];pos+=dn;assert h['port']=='result'
    if h['type']=='start':assert not started and h['representation']=='bytes';started=True
    elif h['type']=='data':assert started and not ended;out.extend(chunk)
    elif h['type']=='end':assert started and not ended;ended=True
    else:raise AssertionError(h)
   assert started and ended;return bytes(out)
  d=fixture('valid',f['data'].encode(),f['params']);actual=payload(call(d));assert actual==f['expected'].encode(),(args.only,actual,f['expected']);print('PASS chunked Arrow execution',args.only,flush=True)
  request={'inputs':{'source':{'representations':['bytes'],'formats':['text'],'certainty':'known','sample':{'representation':'bytes','format':'text','data_base64':base64.b64encode(f['data'].encode()).decode(),'truncated':False}}},'params':f['params'],'preview':{'max_rows':10,'max_bytes':256}}
  (d/'request.json').write_text(json.dumps(request));out=json.loads(call(d,'infer'));desc=out['outputs']['result'];assert desc['representations']==['bytes'];assert len(json.dumps(desc['sample'],separators=(',',':')).encode())<=256;print('PASS inference and bounded preview',args.only,flush=True)
  invalid=fixture('invalid',f['data'].encode(),{'unsupported':True});call(invalid,ok=False);request['params']={'unsupported':True};(invalid/'request.json').write_text(json.dumps(request));call(invalid,'infer',False);print('PASS execution/inference parameter rejection',args.only,flush=True)
  broken=fixture('utf8',b'\xff',f['params']);call(broken,ok=False);print('PASS invalid UTF-8 rejection',args.only,flush=True)
 report={'operator':s['name']+'@'+s['version'],'artifact_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'runtime':args.runtime,'execution_cases':1,'inference_preview_cases':1,'invalid_parameter_cases':2,'encoding_cases':1,'upstream':s['upstream'],'scope':s['scope'],'status':'passed'}
 if args.report:args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(report),flush=True)
if __name__=='__main__':main()
