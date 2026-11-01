"""One interpreter, explicit process(ctx) callables, bounded in-memory Arrow links."""
import base64
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import importlib.abc
import importlib.machinery
import builtins
import types
import zipfile
import io
import json
import os
from pathlib import Path
import queue
import struct
import subprocess
import sys
import tempfile
import threading
import time
import pyarrow as pa


def main(directory):
    directory=Path(directory)
    sdk=importlib.util.spec_from_file_location('xstream_operator',directory/'group-sdk.py',submodule_search_locations=[str(directory)])
    module=importlib.util.module_from_spec(sdk);sys.modules['xstream_operator']=module;sdk.loader.exec_module(module)
    spec=json.loads((directory/'group.json').read_text())
    external={x['port']:x for x in json.loads((directory/'manifest.json').read_text())}
    failed=threading.Event();failure=[];lock=threading.RLock();threads=[]
    stdout=sys.stdout.buffer
    def active():
        if failed.is_set():raise RuntimeError('group failed') from failure[0]
    def fail(exc):
        with lock:
            if not failure:failure.append(exc)
            failed.set()
    def emit(header,payload=b''):
        h=json.dumps(header).encode()
        with lock:
            stdout.write(struct.pack('>II',len(h),len(payload))+h+payload);stdout.flush()
    record_ports=set()
    def observe(task,direction,port,h,payload):
        n=dict(type='task_metric',task=task,direction=direction,port=port,phase=h['type'],payload_bytes=len(payload))
        key=(task,direction,port)
        if 'representation' in h:
            n['representation']=h['representation']
            if h['representation']=='records':record_ports.add(key)
        if h['type']=='data' and key in record_ports:
            with pa.ipc.open_stream(payload) as reader:n['records']=sum(b.num_rows for b in reader)
        emit(n)
    def validate(contract,h):
        if h['representation'] not in contract['representations'] or (contract['formats'] and h['format'] not in contract['formats']):raise ValueError('group port contract mismatch')
        if 'schema' in contract and pa.ipc.read_schema(pa.BufferReader(base64.b64decode(contract['schema'])))!=pa.ipc.read_schema(pa.BufferReader(base64.b64decode(h['schema']))):raise ValueError('schema mismatch')
    def observe_batch(task,direction,port,batch):
        key=(task,direction,port)
        n=dict(type='task_metric',task=task,direction=direction,port=port,phase='data',payload_bytes=batch.nbytes)
        if key in record_ports:n['records']=batch.num_rows
        emit(n)
    class Link:
        def __init__(self,task,port,contract):
            self.task,self.port,self.contract=task,port,contract
            self.meta=None;self.ready=threading.Event();self.events=queue.Queue(1);self.closed=False;self.current=None
        def put(self,value):
            while True:
                active()
                if self.closed:raise RuntimeError('internal input closed')
                try:self.events.put(value,timeout=.1);return
                except queue.Full:pass
        def send(self,h,payload):
            if h['type']=='start':
                validate(self.contract,h)
                if self.ready.is_set():raise ValueError('duplicate internal metadata')
                self.meta=h
                self.schema=pa.ipc.read_schema(pa.BufferReader(base64.b64decode(h['schema']))) if h['representation']=='records' else pa.schema([('data',pa.binary())])
                self.ready.set()
            elif h['type']=='end':self.put(None)
            else:raise ValueError('internal data must be Arrow batches')
            observe(self.task,'inputs',self.port,h,payload)
        def send_batch(self,batch):
            if not self.ready.is_set() or batch.schema!=self.schema:raise ValueError('internal schema changed')
            # Borrow the batch until the consumer advances, including NumPy-backed buffers.
            observe_batch(self.task,'inputs',self.port,batch)
            released=threading.Event()
            self.put((batch,released))
            while not released.wait(.1):active()
        def __iter__(self):return self
        def __next__(self):
            if self.current is not None:self.current.set();self.current=None
            while True:
                active()
                if self.closed:raise StopIteration
                try:
                    value=self.events.get(timeout=.1)
                    if value is None:self.close();raise StopIteration
                    batch,self.current=value
                    return batch
                except queue.Empty:pass
        def close(self):
            self.closed=True
            if self.current is not None:self.current.set();self.current=None
            while True:
                try:
                    value=self.events.get_nowait()
                    if value is not None:value[1].set()
                except queue.Empty:break
    # Private module namespaces keep identical module names in independent bundles
    # from shadowing each other, including imports performed inside process(ctx).
    bundle_roots={}
    original_import=builtins.__import__
    class BundleFinder(importlib.abc.MetaPathFinder):
        def find_spec(self,fullname,path=None,target=None):
            prefix=fullname.split('.',1)[0]
            if prefix not in bundle_roots or '.' not in fullname:return None
            found=importlib.machinery.PathFinder.find_spec(fullname,path)
            if found is None:return None
            loader=found.loader
            if loader is not None and hasattr(loader,'get_code'):
                class Loader(importlib.abc.Loader):
                    def create_module(self,spec):return None
                    def exec_module(self,mod):
                        def private_import(name,globals=None,locals=None,fromlist=(),level=0):
                            root=bundle_roots[prefix]
                            top=name.split('.',1)[0]
                            local=(root/(top+'.py')).is_file() or (root/(top+'.pyc')).is_file() or (root/top).is_dir()
                            if level==0 and local and top!='xstream_operator':
                                qualified=prefix+'.'+name
                                imported=importlib.import_module(qualified)
                                if fromlist:
                                    for child in fromlist:
                                        if child!='*' and not hasattr(imported,child) and hasattr(imported,'__path__'):
                                            try:importlib.import_module(qualified+'.'+child)
                                            except ModuleNotFoundError as exc:
                                                if exc.name!=qualified+'.'+child:raise
                                    return imported
                                return sys.modules[prefix+'.'+top]
                            return original_import(name,globals,locals,fromlist,level)
                        mod.__dict__['__builtins__']=dict(vars(builtins),__import__=private_import)
                        exec(loader.get_code(fullname),mod.__dict__)
                found.loader=Loader()
            return found
    sys.meta_path.insert(0,BundleFinder())
    packages=tempfile.TemporaryDirectory(prefix='xstream-group-packages-')
    prepared={};functions={}
    for step in spec['steps']:
        artifact=Path(step['artifact']).resolve()
        if artifact not in prepared:
            prefix='_xstream_bundle_'+str(len(prepared))
            if artifact.suffix in ('.zip','.whl'):
                root=Path(packages.name)/prefix;root.mkdir()
                if artifact.suffix=='.whl':
                    subprocess.run([sys.executable,'-m','pip','install','--no-index','--no-deps','--no-compile','--target',str(root),str(artifact)],check=True,stdout=sys.stderr)
                else:
                    with zipfile.ZipFile(artifact) as archive:archive.extractall(root)
            else:root=artifact.parent
            bundle_roots[prefix]=root
            package=types.ModuleType(prefix);package.__path__=[str(root)];sys.modules[prefix]=package
            prepared[artifact]=prefix
        prefix=prepared[artifact]
        entry=step['entrypoint'] if artifact.suffix in ('.whl','.zip') else artifact.stem
        m=importlib.import_module(prefix+'.'+entry)
        fn=getattr(m,'process',None)
        if not callable(fn):raise ValueError('Python group requires process(ctx)')
        functions[step['id']]=fn
    links={};targets={}
    for step in spec['steps']:
        for port,b in step['bindings'].items():
            if 'task' in b:
                key=(b['task'],b['port'])
                if key in links:raise ValueError('fan-out unsupported')
                link=Link(step['id'],port,step['inputs'][port]);links[key]=link;targets[(step['id'],port)]=link
    selected={(b['task'],b['port']):p for p,b in spec['outputs'].items()};metadata=set();metadata_ready=threading.Event()
    def execute(step,work):
        task=step['id'];d=Path(work)/task;d.mkdir();manifest=[];streams={};batch_inputs={}
        try:
            for port,b in sorted(step['bindings'].items()):
                if 'task' in b:
                    link=targets[(task,port)]
                    while not link.ready.wait(.1):active()
                    h=link.meta;stream=None;batch_inputs[port]=link
                else:
                    h=external[b['port']];stream=(directory/h['file']).open('rb');validate(step['inputs'][port],h)
                manifest.append(dict(h,port=port,file=port+'.arrow'))
                if stream is not None:streams[port]=stream
            (d/'manifest.json').write_text(json.dumps(manifest));(d/'params.json').write_text(json.dumps(step['params']))
            begun=set();ended=set();ports=set(step['outputs']);representations={}
            def output(h,payload):
                active();port=h['port'];key=(task,port)
                kind=h['type']
                if kind=='start':
                    if port in begun:raise ValueError('duplicate output')
                    validate(step['outputs'].get(port,dict(representations=['bytes','records'],formats=[])),h);begun.add(port);representations[port]=h['representation']
                elif kind=='data':
                    if port not in begun or port in ended:raise ValueError('invalid output data')
                elif kind=='end':
                    if port not in begun or port in ended:raise ValueError('invalid output end')
                    ended.add(port)
                else:raise ValueError('invalid output event')
                observe(task,'outputs',port,h,payload)
                if key in links:links[key].send(h,payload)
                if key in selected:
                    if kind!='start':
                        while not metadata_ready.wait(.1):active()
                    emit(dict(h,port=selected[key]),payload)
                    if kind=='start':
                        with lock:
                            metadata.add(key)
                            if len(metadata)==len(selected):metadata_ready.set()
            def output_batch(port,batch):
                active();key=(task,port)
                if port not in begun or port in ended:raise ValueError('batch before metadata or after end')
                observe_batch(task,'outputs',port,batch)
                if key in links:links[key].send_batch(batch)
                if key in selected:
                    while not metadata_ready.wait(.1):active()
                    if representations[port]=='bytes':
                        payloads=[v.as_py() for v in batch.column(0)]
                    else:
                        sink=pa.BufferOutputStream()
                        with pa.ipc.new_stream(sink,batch.schema) as writer:writer.write_batch(batch)
                        payloads=[sink.getvalue().to_pybytes()]
                    for payload in payloads:emit(dict(type='data',port=selected[key]),payload)
            emit(dict(type='task_state',task=task,state='running',runtime_pid=os.getpid()))
            with module.OperatorContext(['operator',str(d),str(d/'params.json')],input_streams=streams,emit=output,batch_inputs=batch_inputs,emit_batch=output_batch) as ctx:functions[task](ctx)
            if ended!=begun or not ports.issubset(ended):raise ValueError('incomplete output')
            emit(dict(type='task_state',task=task,state='succeeded',runtime_pid=os.getpid()))
        except BaseException as e:fail(e);raise
        finally:
            for stream in streams.values():stream.close()
    with tempfile.TemporaryDirectory(prefix='dacp-group-') as work:
        pool=ThreadPoolExecutor(max_workers=len(spec['steps']))
        futures=[pool.submit(execute,s,work) for s in spec['steps']]
        try:
            while any(not f.done() for f in futures):active();time.sleep(.02)
            for f in futures:f.result()
            active()
        finally:
            pool.shutdown(wait=not failed.is_set(),cancel_futures=True)
            for t in threads:t.join(2)
    if failure:raise RuntimeError('group failed') from failure[0]

if __name__=='__main__':
    try:main(sys.argv[1])
    except BaseException:
        import traceback
        traceback.print_exc(file=sys.stderr);sys.stderr.flush()
        os._exit(1)
