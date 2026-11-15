"""Real Docker smoke test: run with access to Docker (sudo if required).
Requires staged xstream-runner and an installed Python+Arrow runtime image.
Uses an isolated temporary operator library, never a production catalog.
"""
import argparse,json,subprocess,tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--image',default='xstream/operator-python:1.0');p.add_argument('--runner',type=Path,default=Path(__file__).resolve().parents[1]/'xstream-engine/target/universal/stage/bin/xstream-runner');p.add_argument('--repository',default='http://127.0.0.1:8820');a=p.parse_args()
with tempfile.TemporaryDirectory(prefix='xstream-tmp-policy-') as root:
 r=Path(root);op=r/'library/tmp_probe/1.0';op.mkdir(parents=True)
 # Python is explicit test code, not interpolation of Job parameters.
 (op/'operator.yaml').write_text('''name: tmp_probe
version: '1.0'
role: source
outputs: {result: {view: bytes}}
image: '''+json.dumps(a.image)+'''
executor: command
entrypoint: /usr/local/bin/python
executor_config:
  args: ['-c', 'import os; f=open("/tmp/probe","wb"); [f.write(b"x"*1048576) for _ in range(12)]; f.close(); os.unlink("/tmp/probe"); print("TMP_OK")']
  stdin: null
  stdout: {port: result, representation: bytes, format: text}
inputs: {}
icon: {path: 'M3 3h18v18H3z', color: '#14866b'}
parameters: {type: object, properties: {}, required: [], additionalProperties: false}
''')
 (r/'config.yaml').write_text(json.dumps({'remote_operator_library_urls':[a.repository],'local_operator_library':str(r/'library'),'operator_cache':str(r/'cache'),'execution_directory':str(r/'history'),'operator_fusion':'isolated','execution_resources':{'max_tmp_size_mb':512,'max_memory_mb':1024}}))
 (r/'job.yaml').write_text('''name: Temporary quota smoke
steps:
- id: probe
  operator: tmp_probe@1.0
  inputs: {}
  params: {}
- id: client_sink
  operator: client_sink@1.0
  inputs: {result: probe}
''');(r/'vars.yaml').write_text('{}')
 for tmp,expected in [(8,False),(16,True)]:
  (r/'policy.yaml').write_text(json.dumps({'resources':{'tmp_size_mb':tmp,'memory_mb':512}}))
  run=subprocess.run([str(a.runner.resolve()),str(r/'config.yaml'),str(r/'job.yaml'),str(r/'vars.yaml'),str(r/'policy.yaml')],capture_output=True,timeout=90)
  if expected:assert run.returncode==0 and b'TMP_OK' in run.stdout,run.stderr.decode()[-5000:]
  else:assert run.returncode!=0 and b'TMP_OK' not in run.stdout and b'No space left on device' in run.stderr,run.stderr.decode()[-5000:]
  print(json.dumps({'tmp_size_mb':tmp,'success':run.returncode==0,'expected_success':expected}),flush=True)
