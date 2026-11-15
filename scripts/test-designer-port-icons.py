#!/usr/bin/env python3
"""Check compact, accessible port icons using the real Designer rendering function."""
import argparse
import asyncio
from pathlib import Path
from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / 'xstream-designer/src/main/resources/web'

async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chromium', required=True)
    args = parser.parse_args()
    app = (WEB / 'app.js').read_text()
    renderer = app[app.index('function labelPort('):app.index('\nfunction color(', app.index('function labelPort('))]
    async with async_playwright() as p:
        browser = await p.chromium.launch(executable_path=args.chromium, args=['--no-sandbox'])
        page = await browser.new_page(viewport={'width': 1000, 'height': 600})
        await page.set_content('<div class="card"><div class="ports"><div class="in"></div><div class="out"></div></div></div>')
        await page.add_style_tag(content=(WEB / 'style.css').read_text())
        await page.add_script_tag(content=(WEB / 'port-views.js').read_text())
        await page.evaluate('app => {new Function(app)}', app)
        await page.add_script_tag(content='''
          function el(tag,text,cls){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;}
          const definition={inputs:{source:{representations:['records']}},outputs:{}};
          function manifest(){return definition;}
        ''' + renderer)
        await page.evaluate('''() => {
          for(const view of ['bytes','records','array','items','sequence','variable','plugin_view']){
            definition.outputs[view]={view};
            const button=el('button');button.id=view;labelPort(button,{},view);document.querySelector('.out').append(button);
          }
          for(const [id,port] of [['input','source'],['pending','missing']]){
            const button=el('button');button.id=id;labelPort(button,{},port,true);document.querySelector('.in').append(button);
          }
        }''')
        paths=[]
        for view in ['bytes','records','array','items','sequence','variable','plugin_view']:
            icon=page.locator(f'#{view} .port-view')
            await expect(icon).to_have_attribute('data-view',view)
            await expect(icon).to_have_text('')
            await expect(icon).to_have_attribute('role','img')
            assert view in await icon.get_attribute('aria-label') or view=='variable'
            assert await icon.get_attribute('title')
            assert (await icon.bounding_box())['width']==18
            paths.append(await icon.locator('path').get_attribute('d'))
        assert len(set(paths))==len(paths)
        await expect(page.locator('#input .port-view')).to_have_attribute('data-view','records')
        await expect(page.locator('#pending .port-view')).to_have_attribute('data-view','pending')
        for actual in ['records','bytes','items','array']:
            await page.evaluate('''actual => {
              globalThis.report={steps:{read:{outputs:{variable:{representations:[actual]}}}}};
              labelPort(document.querySelector('#variable'),{},'variable');
            }''', actual)
            await expect(page.locator('#variable .port-view')).to_have_attribute('data-view','variable')
        # A multi-type list must never turn into a wide, slash-separated interface label.
        assert await page.evaluate("PortViews.input({inputs:{source:{representations:['bytes','records']}}},'source')") is None
        await browser.close()
    print('PASS: compact icons, accessible names, custom Views, loading state and variable invariance')

if __name__ == '__main__':
    asyncio.run(main())
