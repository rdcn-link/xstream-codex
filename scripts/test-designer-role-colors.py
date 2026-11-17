#!/usr/bin/env python3
"""Verify role colors across icon bodies, role/type badges and canvas borders."""
import argparse
from playwright.sync_api import sync_playwright, expect

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url',default='http://127.0.0.1:8840')
parser.add_argument('--repository-url',default='http://127.0.0.1:8820')
parser.add_argument('--chromium',required=True)
args=parser.parse_args()
expected={'source':'rgb(22, 131, 93)','transform':'rgb(55, 106, 199)','sink':'rgb(191, 116, 22)'}
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
 page=browser.new_page(viewport={'width':1500,'height':1000});errors=[]
 page.on('pageerror',lambda error:errors.append(str(error)))
 page.goto(args.url);expect(page.locator('#repository-state')).to_have_text(__import__('re').compile(r'\d+ operators'),timeout=30000)
 actual=page.evaluate('''() => {
  const host=document.createElement('div');host.id='role-color-test';document.body.append(host);const results=[];
  for(const role of ['source','transform','sink'])for(const type of ['sdk','command','script']){
   const mark=operatorMark({role,type,icon:{path:'M3 5h18v14H3z',color:'#ff00ff'}},false);host.append(mark);
   results.push({role,type,body:getComputedStyle(mark.querySelector('svg')).color,roleBadge:getComputedStyle(mark.querySelector('.operator-role-badge')).color,typeBadge:getComputedStyle(mark.querySelector('.operator-type-badge')).color});
  }
  host.remove();return results;
 }''')
 for row in actual:
  assert row['body']==row['roleBadge']==row['typeBadge']==expected[row['role']],row
 # Match real canvas border colors against the corresponding icon.
 page.evaluate('''() => {const steps=['source','transform','sink'].map((role,i)=>{const op=operators.find(o=>o.role===role);if(!op)throw Error('Missing role '+role);return {id:'role_'+role,operator:op.name+'@'+op.version,inputs:{},params:{}}});job={name:'Role colors',steps};positions={};render();}''')
 for role,color in expected.items():
  card=page.locator('[data-task="role_'+role+'"]');assert card.evaluate('(e)=>getComputedStyle(e).borderTopColor')==color
  assert card.locator('.step-mark svg').evaluate('(e)=>getComputedStyle(e).color')==color
 repository=browser.new_page();repository.goto(args.repository_url)
 for role,color in expected.items():
  mark=repository.locator('.operator-mark.role-'+role).first
  assert mark.count(),role
  for selector in ['svg','.mark-role','.mark-type']:
   assert mark.locator(selector).evaluate('(e)=>getComputedStyle(e).color')==color,(role,selector)
 assert not errors,errors
 browser.close()
print('PASS: all 9 role/type combinations, canvas cards and repository icons use role colors')
