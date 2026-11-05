#!/usr/bin/env python3
"""Mixed Scala/Python operators: Airflow file handoffs versus XStream streams.
Optional LangGraph model tool scheduling uses local Codex API configuration. Inputs are generated before timing; final CSV is hashed, not saved.
"""
import argparse, concurrent.futures, contextlib, csv, hashlib, importlib.metadata, io, json, os
from pathlib import Path
import platform, random, shutil, statistics, struct, subprocess, sys, threading, time
from urllib.request import urlopen
import base64
import pyarrow as pa
import psutil
import yaml

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
FIELDS=['station_id','sequence','temperature','quality','humidity','wind','payload']
OPS={'parse':'piflow_csv_parser','filter':'piflow_filter','warm_filter':'piflow_filter','join':'piflow_join','sql':'piflow_execute_sql_stop','csv':'piflow_csv_save'}
OP_VERSIONS={kind:('1.2' if kind=='filter' else '2.0') for kind in OPS}
BYTES_SCHEMA=pa.schema([('data',pa.binary())])

def emit_json(path,value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,indent=2)+'\n');temporary.replace(path)

def observation(i):
    return [f'S{i%1024:04d}',str(i),str(i%80-20),str(int(i%10==0)),str(30+i%65),str(i%15),'alpha,beta,'+str(i%97).zfill(2)+'x'*52]

def station(i):return [f'S{i:04d}',f'R{i%8}',str(i*3)]

def bytes_ipc(raw,target):
    with target.open('wb') as out,pa.ipc.new_stream(out,BYTES_SCHEMA) as writer,raw.open('rb') as source:
        while chunk:=source.read(65536):writer.write_batch(pa.record_batch([pa.array([chunk],type=pa.binary())],schema=BYTES_SCHEMA))

def make_input(directory,rows):
    directory.mkdir(parents=True,exist_ok=True)
    raw=directory/'observations.csv';dimension=directory/'stations.csv'
    with raw.open('w',newline='') as out:
        writer=csv.writer(out,lineterminator='\n');writer.writerow(FIELDS);writer.writerows(observation(i) for i in range(rows))
    with dimension.open('w',newline='') as out:
        writer=csv.writer(out,lineterminator='\n');writer.writerow(['station_id','region','altitude']);writer.writerows(station(i) for i in range(1024))
    for path in (raw,dimension):bytes_ipc(path,path.with_suffix('.arrow'))
    return {'rows':rows,'bytes':raw.stat().st_size,'csv_sha256':hash_file(raw),'stations_bytes':dimension.stat().st_size}

def hash_file(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        while data:=f.read(1024*1024):h.update(data)
    return h.hexdigest()

def step(id,kind,inputs,params=None):return dict(id=id,kind=kind,inputs=inputs,params=params or {})

def workflow(name):
    parse=step('parse','parse',{'source':'observations'})
    quality=step('quality','filter',{'source':'parse'},{'condition':"quality = '0'"})
    if name=='pipeline':return [parse,quality,step('warm','warm_filter',{'source':'quality'},{'condition':'CAST(temperature AS INTEGER) >= 25'}),step('export','csv',{'source':'warm'})]
    if name=='join':return [parse,step('stations','parse',{'source':'dimension'}),step('combine','join',{'left':'parse','right':'stations'},{'joinMode':'inner','correlationColumn':'station_id'}),step('quality','filter',{'source':'combine'},{'condition':"quality = '0'"}),step('export','csv',{'source':'quality'})]
    if name=='aggregate':return [parse,quality,step('stats','sql',{'source':'quality'},{'viewName':'obs','sql':'SELECT station_id, COUNT(*) AS n, SUM(CAST(temperature AS INTEGER)) AS total FROM obs GROUP BY station_id ORDER BY station_id'}),step('export','csv',{'source':'stats'})]
    raise ValueError(name)

def oracle(name,rows):
    digest=hashlib.sha256();count=0;length=0
    class Output:
        def write(self,text):
            nonlocal length
            data=text.encode();length+=len(data);digest.update(data);return len(text)
    writer=csv.writer(Output(),lineterminator='\n')
    if name=='aggregate':
        groups={}
        for i in range(rows):
            row=observation(i)
            if row[3]=='0':
                v=groups.setdefault(row[0],[0,0]);v[0]+=1;v[1]+=int(row[2])
        writer.writerow(['station_id','n','total'])
        for key,value in sorted(groups.items()):writer.writerow([key,*value]);count+=1
    else:
        writer.writerow(FIELDS+(['region','altitude'] if name=='join' else []))
        for i in range(rows):
            row=observation(i)
            if row[3]!='0' or name=='pipeline' and int(row[2])<25:continue
            if name=='join':row+=station(i%1024)[1:]
            writer.writerow(row);count+=1
    return dict(sha256=digest.hexdigest(),bytes=length,rows=count)

class Ledger:
    def __init__(self):self.reset()
    def reset(self):self.lock=threading.Lock();self.step_details=[];self.intermediate_bytes=0;self.input_read_bytes=0
    def add(self,detail):
        with self.lock:self.step_details.append(detail);self.intermediate_bytes+=detail['written_bytes'];self.input_read_bytes+=detail['input_bytes']

def read_exact(stream,size):
    out=bytearray()
    while len(out)<size:
        part=stream.read(size-len(out))
        if not part:raise EOFError('truncated operator frame')
        out.extend(part)
    return bytes(out)

class FileExecutor:
    def __init__(self,artifacts,docker_prefix,directory,ledger,fsync):self.artifacts=artifacts;self.prefix=docker_prefix;self.directory=directory;self.ledger=ledger;self.fsync=fsync
    def run(self,s,inputs):
        started=time.perf_counter();op=self.artifacts[s['kind']];directory=self.directory/s['id'];directory.mkdir()
        manifest=[];mounts=[]
        for i,(port,a) in enumerate(sorted(inputs.items())):
            filename=str(i)+'.arrow';(directory/filename).touch();manifest.append(dict(port=port,file=filename,representation=a['representation'],format=a['format']))
            mounts+=['--mount',f'type=bind,src={a["path"]},dst=/input/{filename},readonly']
        (directory/'manifest.json').write_text(json.dumps(manifest));(directory/'params.json').write_text(json.dumps(s['params']))
        suffix=Path(op['artifact_path']).suffix
        mounted='/operator/operator'+suffix
        if op['executor']=='python':
            entry='/usr/local/bin/python'
            launch="import runpy,sys; artifact,module=sys.argv[1:3]; sys.path.insert(0,artifact); sys.argv=[module]+sys.argv[3:]; runpy.run_module(module,run_name='__main__')"
            arguments=['-c',launch,mounted,op['entrypoint']]
        else:
            entry='/opt/java/bin/java'
            arguments=['--add-opens=java.base/java.nio=ALL-UNNAMED','-Dio.netty.tryReflectionSetAccessible=true','-cp',mounted+':/opt/lib/*:/opt/xstream/jars/*',op['entrypoint']]
        command=self.prefix+['docker','run','--rm','--network','none','--read-only','--log-driver','none','--pids-limit','128','--memory','1g','--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=512m','--env','PYTHONDONTWRITEBYTECODE=1','--label','xstream.benchmark='+str(self.directory.name),'--mount',f'type=bind,src={directory},dst=/input,readonly','--mount',f'type=bind,src={op["artifact_path"]},dst={mounted},readonly',*mounts,'--entrypoint',entry,op['image'],*arguments,'operator','/input','/input/params.json']
        log=(directory/'stderr.log').open('wb');process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=log)
        writer=out=None;output=None;begun=ended=False;first=None;sha=hashlib.sha256();length=lines=0;final=s['id']=='export';frames=0
        try:
            while True:
                prefix=process.stdout.read(8)
                if not prefix:break
                if len(prefix)<8:prefix+=read_exact(process.stdout,8-len(prefix))
                nh,nd=struct.unpack('>II',prefix)
                if nh>1048576 or nd>134217728:raise ValueError('operator frame exceeds bounds')
                header=json.loads(read_exact(process.stdout,nh));data=read_exact(process.stdout,nd)
                if header.get('port')!='result':raise ValueError(header)
                kind=header['type']
                if kind=='start':
                    if begun:raise ValueError('duplicate start')
                    begun=True;representation=header['representation'];format=header['format']
                    if final:
                        if representation!='bytes':raise ValueError('final output must be CSV bytes')
                    else:
                        output=directory/'result.arrow';out=output.open('wb')
                        schema=BYTES_SCHEMA if representation=='bytes' else pa.ipc.read_schema(pa.BufferReader(base64.b64decode(header['schema'])))
                        writer=pa.ipc.new_stream(out,schema)
                elif kind=='data':
                    if not begun or ended:raise ValueError('data outside stream')
                    frames+=1
                    if final:
                        if data and first is None:first=time.perf_counter()
                        length+=len(data);lines+=data.count(b'\n');sha.update(data)
                    elif representation=='bytes':writer.write_batch(pa.record_batch([pa.array([data],type=pa.binary())],schema=BYTES_SCHEMA))
                    else:
                        with pa.ipc.open_stream(data) as reader:
                            for batch in reader:writer.write_batch(batch)
                elif kind=='end':ended=True
                else:raise ValueError('unexpected frame '+kind)
            code=process.wait(timeout=30)
            if code or not begun or not ended:raise RuntimeError(f'{s["id"]} failed ({code}): '+(directory/'stderr.log').read_text(errors='replace')[-5000:])
            if writer:writer.close();writer=None
            if out:
                out.flush()
                if self.fsync:os.fsync(out.fileno())
                out.close();out=None
            report=dict(step=s['id'],wall_s=time.perf_counter()-started,input_bytes=sum(Path(a['path']).stat().st_size for a in inputs.values()),written_bytes=0 if final else output.stat().st_size,data_frames=frames)
            self.ledger.add(report)
            if final:return dict(sha256=sha.hexdigest(),bytes=length,rows=max(0,lines-1),first_data_at=first)
            return dict(path=str(output),representation=representation,format=format)
        finally:
            if process.poll() is None:process.kill();process.wait()
            if writer:writer.close()
            if out:out.close()
            log.close()

class Host:
    def __init__(self,config,log):
        cp=(HERE/'target/classpath.txt').read_text().strip()
        command=['java','--add-opens=java.base/java.nio=ALL-UNNAMED','-Dio.netty.tryReflectionSetAccessible=true','-XX:ActiveProcessorCount=4','-Xmx2g','-cp',cp,'benchmarks.Host',str(config)]
        self.log=log.open('wb');start=time.perf_counter();self.p=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,bufsize=1)
        ready=self.p.stdout.readline()
        if not ready or not json.loads(ready).get('ready'):raise RuntimeError('XStream host startup failed; see '+str(log))
        self.startup_s=time.perf_counter()-start
    def run(self,path):
        self.p.stdin.write(json.dumps({'job':str(path)})+'\n');self.p.stdin.flush();line=self.p.stdout.readline()
        if not line:raise RuntimeError('XStream host exited')
        return json.loads(line)
    def close(self):
        self.p.stdin.close()
        try:self.p.wait(timeout=30)
        except subprocess.TimeoutExpired:self.p.kill();self.p.wait()
        self.log.close()

def artifact_catalog(url,directory,prefix):
    result={};directory.mkdir(exist_ok=True)
    for kind,name in OPS.items():
        with urlopen(url.rstrip('/')+'/v1/operators/'+name+'/'+OP_VERSIONS[kind]) as r:manifest=json.load(r)
        descriptor=manifest['artifact'];path=directory/(descriptor['sha256']+Path(descriptor['filename']).suffix)
        if not path.exists():
            with urlopen(url.rstrip('/')+'/v1/blobs/sha256/'+descriptor['sha256']) as r:path.write_bytes(r.read())
        assert hash_file(path)==descriptor['sha256']
        image=manifest.get('environment',manifest['runtime'])['image']
        subprocess.run(prefix+['docker','image','inspect',image],stdout=subprocess.DEVNULL,check=True)
        result[kind]=dict(name=name,version=OP_VERSIONS[kind],executor=manifest['execution']['executor'],artifact_sha256=descriptor['sha256'],artifact_path=str(path),image=image,entrypoint=manifest['execution']['entrypoint'])
    return result

def write_job(name,data_dir,path):
    tasks=[dict(id='observations',operator='read_item@1.0',inputs={},params={'uri':'context:/bench/'+data_dir.name+'/observations.csv','view':'bytes'})]
    if name=='join':tasks.append(dict(id='dimension',operator='read_item@1.0',inputs={},params={'uri':'context:/bench/'+data_dir.name+'/stations.csv','view':'bytes'}))
    for s in workflow(name):tasks.append(dict(id=s['id'],operator=OPS[s['kind']]+'@'+OP_VERSIONS[s['kind']],inputs=s['inputs'],params=s['params']))
    path.write_text(yaml.safe_dump(dict(name='Benchmark '+name,steps=tasks,outputs={'result':'export'}),sort_keys=False))

def report(data,output):
    groups={}
    for s in data['samples']:
        if s['warmup'] or s.get('state')!='succeeded':continue
        groups.setdefault((s['workflow'],s['input_rows'],s['method']),[]).append(s)
    rows=[]
    for (name,count,method),ss in sorted(groups.items()):
        wall=[s['wall_s'] for s in ss];first=[s['first_data_s'] for s in ss if s.get('first_data_s') is not None]
        rows.append(dict(workflow=name,input_rows=count,method=method,repeats=len(ss),median_s=statistics.median(wall),min_s=min(wall),max_s=max(wall),median_first_data_s=statistics.median(first) if first else None,intermediate_MiB=statistics.median(s['intermediate_payload_file_bytes']/1048576 for s in ss)))
    data['summary']=rows;emit_json(output/'results.json',data)
    lines=['# Measured orchestration benchmark','',('LLM tool orchestration included; API time is timed.' if 'llm-tools' in data['config']['methods'] else 'No LLM orchestration is included.')+' All results must pass the independent CSV oracle.','','| Workflow | Rows | Method | n | Median s | Min–max s | First data s | Intermediate MiB |','|---|---:|---|---:|---:|---:|---:|---:|']
    for r in rows:lines.append(f'| {r["workflow"]} | {r["input_rows"]:,} | {r["method"]} | {r["repeats"]} | {r["median_s"]:.3f} | {r["min_s"]:.3f}–{r["max_s"]:.3f} | {r["median_first_data_s"]:.3f} | {r["intermediate_MiB"]:.2f} |')
    lines+=['','Speedup = Airflow-file median / XStream median. These measure the specified file/stream configurations, not every possible Airflow integration. Warm hosts and filesystem cache; container startup, scheduling, serialization, processing and full result consumption are timed. Input generation, image/artifact retrieval and framework startup are excluded and recorded separately. Intermediate file size is logical bytes, not physical disk I/O. No durability claim is made unless fsync is enabled.']
    for name,count in sorted({(r['workflow'],r['input_rows']) for r in rows}):
        rr={r['method']:r for r in rows if r['workflow']==name and r['input_rows']==count}
        if 'airflow-file' in rr:
            for mode in ('xstream-isolated','xstream-fused'):
                if mode in rr:lines.append(f'- {name}, {count:,} rows: {mode} **{rr["airflow-file"]["median_s"]/rr[mode]["median_s"]:.2f}×** relative to Airflow-file.')
    failures=[s for s in data['samples'] if s.get('state')!='succeeded']
    if failures:lines+=['',f'Failed/invalid trials: {len(failures)}. See results.json; excluded from speedup, never treated as success.']
    (output/'report.md').write_text('\n'.join(lines)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--repository',default='http://127.0.0.1:8820')
    p.add_argument('--rows',type=int,nargs='+',default=[10000,100000,250000]);p.add_argument('--workflows',nargs='+',choices=['pipeline','join','aggregate'],default=['pipeline','join','aggregate'])
    p.add_argument('--codex-home',type=Path,default=Path.home()/'.codex');p.add_argument('--methods',nargs='+',choices=['airflow-file','xstream-isolated','xstream-fused','llm-tools'],default=['airflow-file','xstream-isolated','xstream-fused']);p.add_argument('--repeats',type=int,default=3);p.add_argument('--warmups',type=int,default=1);p.add_argument('--fsync',action='store_true');p.add_argument('--seed',type=int,default=20261105)
    args=p.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    assert args.repeats>=1 and args.warmups>=0 and all(n>0 for n in args.rows)
    if any(n>250000 for n in args.rows) and any(w in args.workflows for w in ['join','aggregate']):p.error('Use <=250000 rows for bounded Join/SQL, or run pipeline separately')
    # Must be started by a Docker-authorized account; never changes production services.
    prefix=[];subprocess.run(['docker','info'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    artifacts=artifact_catalog(args.repository,out/'artifacts',prefix)
    datasets={n:make_input(out/'data'/str(n),n) for n in args.rows}
    expected={(w,n):oracle(w,n) for w in args.workflows for n in args.rows}
    data=dict(timestamp=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),git_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),git_dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),host=dict(platform=platform.platform(),cpu=platform.processor(),logical_cpus=os.cpu_count(),memory_bytes=psutil.virtual_memory().total),versions={n:importlib.metadata.version(n) for n in ['apache-airflow','pyarrow','psutil','PyYAML','langgraph','httpx']},artifacts=artifacts,datasets=datasets,config=vars(args)|{'output':str(out)},samples=[],startup={},methodology={'llm':'not enabled for this run','final_output':'CSV bytes SHA-256, rows and size against independent Python oracle; no final data file','file_mode':'Arrow IPC intermediate files, Airflow LocalExecutor tasks exchange file paths through XCom; retries=0','stream_mode':'Arrow streams, no materialization store and policy forbids materialization; only metadata/log files','host_mode':'persistent, warmed JVM/Python hosts; framework startup excluded and recorded','cache':'OS cache warm; no privileged cache dropping','fsync':args.fsync,'resource_limits':'each operator container 1 GiB, 128 PIDs; host not exclusively reserved; 8 concurrent tasks','input_ingestion':'same CSV; file baseline uses prewrapped Arrow bytes input, XStream reads and wraps raw CSV during timing (conservative to XStream)','file_volume':'sum of intermediate Arrow file lengths; not physical disk traffic'})
    data['config']['output']=str(out);data['config']['codex_home']='<local-codex-config>'
    hosts={};airflow_host=None;model_api=None;llm_run=None
    try:
        if 'llm-tools' in args.methods:
            from llm_adapter import CodexResponses, make_adapter
            model_api=CodexResponses(args.codex_home);llm_run=make_adapter(model_api);data['model_configuration']=model_api.metadata
            data['methodology']['llm']='LangGraph + Responses function calling; fixed DAG, actual mixed Scala/Python tools, Arrow file handles; API latency included, no retries'
        if 'airflow-file' in args.methods:
            from airflow_adapter import AirflowHost
            airflow_host=AirflowHost(out/'airflow');data['startup']['airflow_s']=airflow_host.startup_s
        for method in args.methods:
            if method.startswith('xstream'):
                config=dict(datasets={'bench':{'provider':'directory','source':{'root':str(out/'data')}}},remote_operator_library_urls=[args.repository],operator_cache=str(out/'operator-cache'),execution_directory=str(out/(method+'-history')),operator_fusion_enabled=method.endswith('fused'),max_concurrent_tasks=8,execution_timeout_seconds=1800)
                path=out/(method+'.yaml');path.write_text(yaml.safe_dump(config));hosts[method]=Host(path,out/(method+'.log'));data['startup'][method+'_s']=hosts[method].startup_s
        jobs=out/'jobs';jobs.mkdir()
        for w in args.workflows:
            for n in args.rows:write_job(w,out/'data'/str(n),jobs/f'{w}-{n}.yaml')
        rng=random.Random(args.seed)
        for n in args.rows:
            for w in args.workflows:
                for repeat in range(-args.warmups,args.repeats):
                    order=list(args.methods);rng.shuffle(order)
                    for method in order:
                        identifier=f'{w}-{n}-{method}-{repeat+args.warmups:02d}'
                        sample=dict(workflow=w,input_rows=n,input_bytes=datasets[n]['bytes']+(datasets[n]['stations_bytes'] if w=='join' else 0),method=method,repeat=repeat,warmup=repeat<0,intermediate_payload_file_bytes=0)
                        try:
                            if method in ('airflow-file','llm-tools'):
                                directory=out/'trials'/identifier;directory.mkdir(parents=True);ledger=Ledger();executor=FileExecutor(artifacts,prefix,directory,ledger,args.fsync)
                                inputs={name:dict(path=str(out/'data'/str(n)/file),representation='bytes',format='csv') for name,file in [('observations','observations.arrow'),('dimension','stations.arrow')]}
                                start=time.perf_counter();result=(airflow_host.run(w,workflow(w),inputs,executor) if method=='airflow-file' else llm_run(workflow(w),inputs,executor));sample['wall_s']=time.perf_counter()-start
                                sample.update(result);sample['first_data_s']=result['first_data_at']-start if result['first_data_at'] else None;sample.pop('first_data_at',None)
                                sample['intermediate_payload_file_bytes']=ledger.intermediate_bytes;sample['logical_input_read_bytes']=ledger.input_read_bytes;sample['steps']=ledger.step_details
                            else:sample.update(hosts[method].run(jobs/f'{w}-{n}.yaml'))
                            if sample.get('state','succeeded')!='succeeded':raise RuntimeError(sample.get('error','execution failed'))
                            for key,value in expected[w,n].items():
                                if sample.get(key)!=value:raise ValueError(f'oracle mismatch for {key}: {sample.get(key)} != {value}')
                            sample['state']='succeeded';sample['throughput_MiB_s']=sample['input_bytes']/1048576/sample['wall_s']
                        except Exception as e:
                            sample.update(state='failed',error=str(e))
                            if hasattr(e,'metrics'):sample.update(e.metrics);sample['wall_s']=e.metrics['agent_wall_s']
                        data['samples'].append(sample);report(data,out)
                        print(json.dumps({k:sample.get(k) for k in ['workflow','input_rows','method','repeat','warmup','state','wall_s','first_data_s','error']}),flush=True)
    finally:
        for host in hosts.values():host.close()
        if model_api:model_api.close()
        if airflow_host:airflow_host.close()
        report(data,out)
    if any(s['state']!='succeeded' for s in data['samples']):raise SystemExit('Benchmark has failed or invalid trials; see results.json')

if __name__=='__main__':main()
