#!/usr/bin/env python3
"""Verify every configured example against its actual target and Item paths."""
import argparse,json,secrets,urllib.request
import yaml
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',default='http://localhost:8840');a=p.parse_args()
def api(endpoint,body):
    request=urllib.request.Request(a.url+'/api/'+endpoint,data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=180) as r:return json.load(r)
examples=api('examples',{})
for e in examples:
    job=yaml.safe_load(e['job_yaml']);target=job.get('designer',{}).get('target','local')
    result=api('infer',{'job_yaml':e['job_yaml'],'variables_yaml':e['variables_yaml'],'target':target,'inference_session':secrets.token_hex(16),'preview':{'max_rows':3,'max_bytes':4096}})
    problems=[]
    for name,step in result['steps'].items():
        problems.extend((name,d) for d in step.get('diagnostics',[]) if d['severity'] in ('error','warning'))
        for port,output in step['outputs'].items():
            if output['certainty']!='known':problems.append((name,port,'unknown schema'))
    assert not problems,(e['id'],problems)
    assert result['outputs'],(e['id'],'missing final output')
    print('PASS',e['id'],target,flush=True)
print('PASS all',len(examples),'configured examples: actual paths, variable resolution, schemas and bounded samples')
