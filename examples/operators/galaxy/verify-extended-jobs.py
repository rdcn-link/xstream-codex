#!/usr/bin/env python3
"""Run every extended sample through the standalone engine after public publication."""
import json,subprocess,time
from pathlib import Path
from urllib.request import urlopen
from urllib.error import HTTPError,URLError
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
def main():
 specs=json.loads((HERE/'extra-specs.json').read_text());results=[]
 for name,s in specs.items():
  deadline=time.monotonic()+3600
  while True:
   try:
    with urlopen('http://127.0.0.1:8820/v1/operators/'+s['name']+'/1.0',timeout=10) as response:json.load(response)
    break
   except HTTPError as e:
    if e.code!=404:raise
   except URLError:pass
   if time.monotonic()>deadline:raise TimeoutError('Publication did not complete: '+name)
   time.sleep(5)
  cmd=['sudo','-n','-u','cnic','-g','docker',str(ROOT/'xstream-engine/target/universal/stage/bin/xstream-runner'),str(HERE/'engine-extended.yaml'),str(HERE/'jobs-extended'/f'{name}.yaml')]
  result=subprocess.run(cmd,cwd=ROOT,capture_output=True,timeout=180)
  assert result.returncode==0,(name,result.stderr.decode(errors='replace')[-6000:])
  assert result.stdout.decode()==s['fixture']['expected'],(name,result.stdout,s['fixture']['expected'])
  results.append({'operator':s['name']+'@1.0','job':f'jobs-extended/{name}.yaml','status':'passed','output':result.stdout.decode()})
  (HERE/'verification-extended-jobs.json').write_text(json.dumps({'status':'passed' if len(results)==len(specs) else 'running','completed':len(results),'total':len(specs),'jobs':results},indent=2)+'\n')
  print('PASS standalone Job',name,flush=True)
if __name__=='__main__':main()
