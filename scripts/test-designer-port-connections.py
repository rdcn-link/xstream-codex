#!/usr/bin/env python3
"""Exercise real Designer connection handlers against declared View contracts."""
import argparse
import asyncio
from pathlib import Path
from playwright.async_api import async_playwright
ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / 'xstream-designer/src/main/resources/web'

async def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chromium',required=True)
    args=parser.parse_args()
    source=(WEB/'app.js').read_text()
    a=source.index('function connectionCompatibility(')
    b=source.index('\nfunction ',source.index('async function connectPort(',a))
    handlers=source[a:b]
    async with async_playwright() as p:
        browser=await p.chromium.launch(executable_path=args.chromium,args=['--no-sandbox'])
        page=await browser.new_page()
        await page.add_script_tag(content=(WEB/'port-views.js').read_text())
        await page.add_script_tag(content='''
          let pendingPort='up:result',revision=0,calls=0,history=0,changes=0,messages=[];
          const declarations={up:{outputs:{result:{view:'bytes'}}},down:{inputs:{source:{representations:['records']}}}};
          let job={steps:[{id:'up',inputs:{}},{id:'down',inputs:{source:'old:result'}}]};
          const clone=n=>JSON.parse(JSON.stringify(n));
          const binding=s=>{const [task,port='result']=s.split(':');return {task,port}};
          function manifest(t){return declarations[t.id]}
          function payload(){return {}}
          function notice(message,error){messages.push({message,error})}
          function remember(){history++}
          function changed(){changes++}
          function render(){}
          function drawPreview(){}
          async function api(){calls++;}
        '''+handlers)
        result=await page.evaluate('''async()=>{
          await connectPort(job.steps[1],'source');
          return {calls,history,changes,messages,pendingPort,input:job.steps[1].inputs.source};
        }''')
        assert result['calls']==result['history']==result['changes']==0,result
        assert result['input']=='old:result' and result['pendingPort']=='up:result',result
        assert 'bytes → records' in result['messages'][-1]['message'],result
        # Matching and either-side variable contracts pass. Inference is irrelevant.
        for output,input_ in [('bytes','bytes'),('records','records'),('variable','records'),('bytes','variable'),('variable','variable'),('array','array'),('items','items')]:
            result=await page.evaluate('''async([output,input])=>{
              declarations.up.outputs.result.view=output;
              declarations.down.inputs.source.representations=[input];
              pendingPort='up:result';globalThis.report={steps:{up:{outputs:{result:{representations:['different']}}}}};
              const before=calls;await connectPort(job.steps[1],'source');
              return {calls:calls-before,input:job.steps[1].inputs.source};
            }''',[output,input_])
            assert result=={'calls':1,'input':'up:result'},(output,input_,result)
        for output,input_ in [('items','bytes'),('records','array'),('array','records'),('custom_a','custom_b')]:
            assert not await page.evaluate('''([a,b])=>PortViews.compatibility({outputs:{out:{view:a}}},'out',{inputs:{in:{representations:[b]}}},'in').allowed''',[output,input_])
        assert not await page.evaluate("PortViews.compatibility(null,'result',declarations.down,'source').allowed")
        assert not await page.evaluate("PortViews.compatibility(declarations.up,'missing',declarations.down,'source').allowed")
        assert not await page.evaluate("connectionCompatibility('up:result',job.steps[0],'source').allowed")
        # Server validation errors and edits during validation preserve the existing Job.
        for failure in ['reject','revision']:
            result=await page.evaluate('''async failure=>{
              declarations.up.outputs.result.view='records';declarations.down.inputs.source.representations=['records'];
              pendingPort='up:result';job.steps[1].inputs.source='old:result';
              api=async()=>{calls++;if(failure==='reject')throw Error('cycle rejected');revision++};
              const before=history;await connectPort(job.steps[1],'source');
              return {input:job.steps[1].inputs.source,history:history-before,error:messages.at(-1).error};
            }''',failure)
            assert result=={'input':'old:result','history':0,'error':True},result
        await browser.close()
    print('PASS: mismatch rejection, variable types, declaration availability and atomic connection validation')
if __name__=='__main__':asyncio.run(main())
