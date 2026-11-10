#!/usr/bin/env python3
"""Run every extended sample through the standalone engine after public publication."""
import argparse,json,subprocess,time
from pathlib import Path
from urllib.request import urlopen
from urllib.error import HTTPError,URLError
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--only',nargs='+');args=parser.parse_args()
 specs=json.loads((HERE/'extra-specs.json').read_text());all_specs=specs
 if args.only:
  assert all(n in specs for n in args.only),'unknown adapter'
  specs={n:specs[n] for n in args.only}
 report=HERE/'verification-extended-jobs.json';previous=json.loads(report.read_text()) if report.exists() else {};results=[v for v in previous.get('jobs',[]) if v['operator'].split('@')[0] not in {s['name'] for s in specs.values()}]
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
  (HERE/'verification-extended-jobs.json').write_text(json.dumps({'status':'passed' if len(results)==len(all_specs) else 'running','completed':len(results),'total':len(all_specs),'jobs':results},indent=2)+'\n')
  print('PASS standalone Job',name,flush=True)
if __name__=='__main__':main()
