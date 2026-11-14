#!/usr/bin/env python3
"""Check explicit Client Sink import/export, wiring, deletion, undo and library insertion."""
import argparse,json
from playwright.sync_api import sync_playwright,expect
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--url',default='http://127.0.0.1:8840')
p.add_argument('--chromium',required=True)
a=p.parse_args()
with sync_playwright() as playwright:
 browser=playwright.chromium.launch(executable_path=a.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
 page=browser.new_page(viewport={'width':1650,'height':1050});errors=[]
 page.on('pageerror',lambda e:errors.append(str(e)))
 descriptor={'representations':['bytes'],'formats':['text'],'certainty':'known'}
 def infer(route):
  job=json.loads(route.request.post_data_json['job_yaml'])
  steps={t['id']:{'outputs':{} if t['operator']=='client_sink@1.0' else {'result':descriptor},'diagnostics':[]} for t in job['steps']}
  report={'steps':steps,'outputs':{'data':descriptor}}
  if route.request.url.endswith('infer_stream'):
   route.fulfill(content_type='application/x-ndjson',body='\n'.join(json.dumps(x) for x in [{'event':'step','step':k,'result':v} for k,v in steps.items()]+[{'event':'complete','report':report}])+'\n')
  else:route.fulfill(json=report)
 page.route('**/api/infer',infer);page.route('**/api/infer_stream',infer)
 page.goto(a.url);expect(page.locator('#repository-state')).to_have_text(__import__('re').compile(r'\d+ operators'),timeout=30000)
 job={'name':'Explicit sink','steps':[{'id':'read','operator':'item_source@1.0','inputs':{},'params':{'uri':'context:/weather/input.txt','view':'bytes'}},{'id':'receive','title':'Client Sink','description':'Return original file bytes','operator':'client_sink@1.0','inputs':{'data':'read:result'}}],'designer':{'version':1,'positions':{'read':{'x':60,'y':90},'receive':{'x':400,'y':90}}}}
 page.evaluate('(job)=>loadDocument(JSON.stringify(job))',job)
 expect(page.locator('.card')).to_have_count(2);expect(page.locator('#outlet')).to_have_count(0)
 sink=page.locator('[data-task="receive"]')
 expect(sink).to_contain_text('Client Sink');expect(sink.locator('[data-output]')).to_have_count(0)
 exported=page.evaluate('async()=>await api("export",payload())')
 document=page.evaluate('(text)=>api("document",{job_yaml:text,variables_yaml:"{}"})',exported['job_yaml'])['job'];assert 'outputs' not in document
 assert document['steps'][1]==job['steps'][1]
 assert document['designer']['positions']['receive']==job['designer']['positions']['receive']
 sink.dblclick();expect(page.locator('#step-description')).to_have_value('Return original file bytes')
 expect(page.locator('#duplicate-step')).to_be_disabled()
 # Deleting and restoring a connection keeps the input port available.
 page.locator('[data-edge="receive:data"]').dispatch_event('click');page.keyboard.press('Delete')
 expect(sink.locator('[data-input="receive:result"]')).to_have_count(1)
 page.locator('[data-output="read:result"]').click();sink.locator('[data-input="receive:result"]').click()
 page.wait_for_function("()=>job.steps.find(t=>t.id==='receive').inputs.result==='read:result'")
 assert page.evaluate('executionDocument().steps[1].id')=='receive'
 # A Sink can be deleted like any other step and restored with its binding.
 sink.click();page.keyboard.press('Delete');expect(page.locator('[data-task="receive"]')).to_have_count(0)
 page.keyboard.press('Control+z');expect(sink).to_have_count(1)
 assert page.evaluate("job.steps.find(t=>t.id==='receive').inputs.result")=='read:result'
 # Starting with a blank canvas, add the actual repository operator.
 page.locator('#new').click();expect(page.locator('.card')).to_have_count(0)
 page.locator('[data-operator="client_sink@1.0"]').first.dblclick()
 expect(page.locator('.card')).to_have_count(1)
 assert page.evaluate("job.steps[0].operator")=='client_sink@1.0'
 page.locator('[data-operator="client_sink@1.0"]').first.dblclick();expect(page.locator('.card')).to_have_count(1)
 assert not errors,errors
 browser.close()
print('PASS: explicit Client Sink round-trip, layout, ports, wiring, delete/undo and repository insertion')
