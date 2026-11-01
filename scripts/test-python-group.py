#!/usr/bin/env python3
"""Exercise shared-interpreter execution with real Arrow streams and bounded links."""
import base64
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import py_compile
import pyarrow as pa
ROOT=Path(__file__).resolve().parents[1]
SOURCE='''from xstream_operator import OperatorContext
def process(ctx):
    source=ctx.input('source')
    if ctx.params.get('extra'):ctx.bytes_output('unused','text')
    if source.representation=='records':
        out=ctx.records_output('result',source.schema)
        import builtins
        for batch in source.records():
            if ctx._batch_inputs:
                assert batch is builtins._xstream_probe_batch, 'internal link serialized the batch'
            else:
                builtins._xstream_probe_batch = batch
            out.write(batch)
    else:
        out=ctx.bytes_output('result','text')
        for chunk in source.bytes():out.write(chunk.upper())
'''
def decode(data):
    frames=[]
    while data:
        h,n=struct.unpack('>II',data[:8]);frames.append((json.loads(data[8:8+h]),data[8+h:8+h+n]));data=data[8+h+n:]
    return frames
class PythonGroup(unittest.TestCase):
    def run_group(self,records=False,mismatch=False,dynamic=False,bundles=None):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp);(d/'ops.py').write_text(SOURCE + ('\ndef infer_outputs(*args):\n    raise AssertionError("execution must not infer")\n' if dynamic else ''))
            shutil.copyfile(ROOT/'xstream-operator-sdk/python/xstream_operator/__init__.py',d/'group-sdk.py')
            shutil.copyfile(ROOT/'xstream-operator-sdk/python/xstream_operator/inference.py',d/'inference.py')
            table=pa.table({'value':[1,None,3,4]}) if records else pa.table({'data':pa.array([b'abc'*30000]*12,type=pa.binary())})
            with (d/'0.arrow').open('wb') as raw:
                with pa.ipc.new_stream(raw,table.schema) as w:w.write_table(table,max_chunksize=2)
            repr='records' if records else 'bytes';fmt='arrow' if records else 'text'
            contract=dict(representations=[repr],formats=['wrong'] if mismatch else [fmt])
            if records:contract['schema']=base64.b64encode(table.schema.serialize().to_pybytes()).decode()
            meta=dict(port='i0',file='0.arrow',representation=repr,format=fmt)
            if records:meta['schema']=base64.b64encode(table.schema.serialize().to_pybytes()).decode()
            (d/'manifest.json').write_text(json.dumps([meta]))
            steps=[]
            for id,binding in [('a',dict(port='i0')),('b',dict(task='a',port='result'))]:
                steps.append(dict(id=id,artifact=str(d/'ops.py'),entrypoint='ops',params={'extra':dynamic},bindings={'source':binding},inputs={'source':contract},outputs={'result':contract}))
            if bundles:
                for i,step in enumerate(steps):
                    package=d/('package'+str(i));package.mkdir()
                    # Identical entrypoint/helper names, deliberately different helper contents.
                    expected='bundle'+str(i)
                    code=SOURCE.replace("    source=ctx.input('source')", "    from helper import identity\n    assert identity() == "+json.dumps(expected)+"\n    source=ctx.input('source')")
                    (package/'ops.py').write_text(code)
                    (package/'helper.py').write_text('def identity(): return '+json.dumps(expected)+'\n')
                    artifact=package/'ops.py'
                    if bundles in ('zip','pyc','whl'):
                        if bundles=='pyc':
                            for name in ['ops','helper']:py_compile.compile(str(package/(name+'.py')),cfile=str(package/(name+'.pyc')),doraise=True)
                        artifact=d/('bundle'+str(i)+('-1.0-py3-none-any.whl' if bundles=='whl' else '.zip'))
                        with zipfile.ZipFile(artifact,'w') as archive:
                            for name in ['ops','helper']:
                                file=name+('.pyc' if bundles=='pyc' else '.py');archive.write(package/file,file)
                            if bundles=='whl':
                                info='bundle'+str(i)+'-1.0.dist-info/'
                                archive.writestr(info+'METADATA','Metadata-Version: 2.1\nName: bundle'+str(i)+'\nVersion: 1.0\n')
                                archive.writestr(info+'WHEEL','Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n')
                                archive.writestr(info+'RECORD','')
                    step['artifact']=str(artifact)
            if dynamic:
                for step in steps:step['outputs']={'result':dict(representations=['bytes','records'],formats=[])}
            (d/'group.json').write_text(json.dumps(dict(steps=steps,outputs={'o0':dict(task='b',port='result')})))
            p=subprocess.run([sys.executable,str(ROOT/'plugins/xstream-plugin-operator-executors/src/main/resources/python-group.py'),str(d)],capture_output=True,timeout=20)
            if mismatch:
                self.assertNotEqual(p.returncode,0);return
            self.assertEqual(p.returncode,0,p.stderr.decode())
            frames=decode(p.stdout);states=[h for h,_ in frames if h['type']=='task_state'];self.assertEqual(len(states),4);self.assertEqual(len({h['runtime_pid'] for h in states}),1)
            outputs=[(h,b) for h,b in frames if not h['type'].startswith('task_')]
            self.assertEqual([h['type'] for h,_ in outputs][::len(outputs)-1],['start','end'])
            chunks=[b for h,b in outputs if h['type']=='data']
            if records:
                batches=[b for chunk in chunks for b in pa.ipc.open_stream(chunk)]
                self.assertEqual(pa.Table.from_batches(batches).to_pydict(),table.to_pydict())
                self.assertTrue(any(h.get('records',0)>0 for h,_ in frames if h['type']=='task_metric'))
            else:self.assertEqual(b''.join(chunks),b'ABC'*30000*12)
    def test_independent_sources_isolate_late_helper_imports(self):self.run_group(records=True,bundles='source')
    def test_independent_zip_bundles_same_entrypoint(self):self.run_group(bundles='zip')
    def test_independent_bytecode_bundles(self):self.run_group(records=True,bundles='pyc')
    def test_independent_wheels(self):self.run_group(bundles='whl')
    def test_execution_uses_stream_headers_without_inference(self):self.run_group(records=True,dynamic=True)
    def test_large_byte_stream_same_pid(self):self.run_group()
    def test_dynamic_byte_output_uses_actual_header(self):self.run_group(dynamic=True)
    def test_records_preserve_schema_nulls_and_batches(self):self.run_group(records=True)
    def test_contract_failure_terminates_without_deadlock(self):self.run_group(mismatch=True)
if __name__=='__main__':unittest.main()
