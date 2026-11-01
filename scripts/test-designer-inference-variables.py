#!/usr/bin/env python3
"""Check variable resolution and invalidation through the live Designer UI."""
import argparse
from playwright.sync_api import sync_playwright, expect
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--url',default='http://localhost:8840')
p.add_argument('--chromium',required=True)
p.add_argument('--item',default='context:/local_files/weather-observations.jsonl')
a=p.parse_args()
with sync_playwright() as pw:
    browser=pw.chromium.launch(executable_path=a.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
    page=browser.new_page();page.add_init_script('localStorage.clear()');page.goto(a.url)
    page.wait_for_function('()=>config && config.targets.length > 0')
    example=next(e for e in page.request.post(a.url+'/api/examples',data={}).json() if e['id']=='variables')
    values='INPUT_URI: '+a.item+'\nCONDITION: temperature_c >= 25\nINCLUDE_HEADER: true\n'
    page.evaluate('(e)=>loadDocument(e.job_yaml,e.variables_yaml)',dict(example,variables_yaml=values))
    def ready():
        page.wait_for_function("()=>!inferenceRunning && !inferencePending && report?.steps?.csv?.outputs?.result",timeout=60000)
        assert page.evaluate("Object.values(report.steps).every(s=>!(s.diagnostics||[]).some(d=>d.severity==='error'))")
    ready()
    assert page.evaluate("job.steps.find(s=>s.id==='csv').params.header")=='$INCLUDE_HEADER'
    page.evaluate('(v)=>{variablesEditor.setText(v);invalidate()}',values.replace('true','false'))
    ready()
    assert page.evaluate("job.steps.find(s=>s.id==='read').params.uri")=='$INPUT_URI'
    page.evaluate('(v)=>{variablesEditor.setText(v);invalidate()}',values.replace('INPUT_URI:', 'OTHER_URI:'))
    page.wait_for_function("()=>inferenceState.includes('missing workflow variable: INPUT_URI')",timeout=30000)
    page.evaluate('(v)=>{variablesEditor.setText(v);invalidate()}',values)
    ready()
    browser.close()
print('PASS: URI/string/boolean variable inference, value changes, missing-variable error and recovery; Job retains variable references')
