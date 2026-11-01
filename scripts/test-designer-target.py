#!/usr/bin/env python3
"""Check confirmed Target switching, saved preferences and live context inference."""
import argparse,json,re
from playwright.sync_api import sync_playwright,expect
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--url',default='http://localhost:8840');p.add_argument('--chromium',required=True)
a=p.parse_args()
with sync_playwright() as playwright:
    browser=playwright.chromium.launch(executable_path=a.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
    page=browser.new_page(viewport={'width':1600,'height':1000});page.add_init_script('localStorage.clear()')
    errors=[];sessions=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    def observe(request):
        if '/api/infer' in request.url:
            n=request.post_data_json;sessions.append(n['inference_session']);assert re.fullmatch('[a-f0-9]{32}',n['inference_session'])
    page.on('request',observe)
    page.goto(a.url);expect(page.locator('#connection')).to_have_text('Connected',timeout=30000)
    config=page.request.get(a.url+'/api/config').json();assert len(config['targets'])>1
    remote=config['targets'][1]
    expect(page.locator('#target-label')).to_have_text('Local')
    page.locator('#library-datasets-tab').click();roots=page.locator('#datasets > .dataset > .dataset-button');expect(roots).to_have_count(len(config['targets']))
    def choose(value,confirm=True):
        page.locator('#change-target').click();page.locator('#target').select_option(value)
        page.locator('#target-confirm' if confirm else '#target-cancel').click()
        expect(page.locator('#target-dialog')).not_to_be_visible()
    choose(remote['value'],False);expect(page.locator('#target-label')).to_have_text('Local');expect(roots).to_have_count(len(config['targets']))
    choose(remote['value']);expect(page.locator('#target-label')).to_have_text(remote['label']);expect(roots).to_have_count(len(config['targets'])-1)
    assert remote['label'] not in roots.all_text_contents()
    other=browser.new_page();other.goto(a.url);expect(other.locator('#target-label')).to_have_text('Local')
    directory=page.request.post(a.url+'/api/datasets',data={'target':remote['value'],'uri':'context:/'}).json()
    assert directory['datasets'];assert all(d['uri'].startswith('context:/') for d in directory['datasets'])
    item_uri=None
    for dataset in directory['datasets']:
        items=page.request.post(a.url+'/api/items',data={'target':remote['value'],'uri':dataset['uri'],'path':'/'}).json()['items']
        for item in items:
            if item['kind']!='folder' and 'records' in item['views']:
                item_uri=dataset['uri'].rstrip('/')+'/'+item['path'].lstrip('/');break
        if item_uri:break
    assert item_uri,'Target fixture needs a records Item'
    job={'name':'Design target regression','steps':[{'id':'read','operator':'read_item@1.0','inputs':{},'params':{'uri':item_uri,'view':'records'}}],'outputs':{'result':'read'},'designer':{'version':1,'target':remote['value']}}
    choose('local');expect(roots).to_have_count(len(config['targets']))
    def load():page.locator('#file').set_input_files({'name':'preferred.yaml','mimeType':'application/yaml','buffer':json.dumps(job).encode()})
    load();expect(page.locator('#target-dialog')).to_be_visible();expect(page.locator('#target-label')).to_have_text('Local')
    page.locator('#target-cancel').click();expect(page.locator('#cards .card')).to_have_count(1);expect(page.locator('#target-label')).to_have_text('Local')
    load();expect(page.locator('#target-dialog')).to_be_visible();page.locator('#target-confirm').click();expect(page.locator('#target-label')).to_have_text(remote['label'])
    expect(page.locator("#cards .port-schema-table")).to_be_visible(timeout=30000)
    assert page.evaluate("report.steps.read.outputs.result.certainty==='known'")
    assert not page.evaluate("report.steps.read.diagnostics.some(d=>d.severity==='error')")
    assert sessions and all(re.fullmatch('[a-f0-9]{32}',s)for s in sessions)
    saved=page.evaluate('JSON.parse(payload().job_yaml).designer');assert saved['target']==remote['value']
    response=page.request.post(a.url+'/api/export',data=page.evaluate('payload()'));assert response.status==200,(response.text(),page.evaluate("JSON.parse(payload().job_yaml).designer"));exported=response.json()['job_yaml'];assert 'target:' in exported
    page.locator('#change-target').click();page.locator('#target').select_option('local');page.keyboard.press('Escape');expect(page.locator('#target-label')).to_have_text(remote['label'])
    job['designer']['target']='dacp://unconfigured.example:18861';load();expect(page.locator('#target-message')).to_contain_text('unavailable design target');page.locator('#target-cancel').click();expect(page.locator('#target-label')).to_have_text(remote['label'])
    # A refresh restores a previously confirmed target without an import dialog.
    draft=page.evaluate('snapshot()')
    fresh_context=browser.new_context();refresh=fresh_context.new_page();refresh.goto(a.url)
    expect(refresh.locator('#connection')).to_have_text('Connected',timeout=30000)
    draft_key=config.get('draft_key','xstream-designer')
    refresh.evaluate('(x)=>localStorage.setItem(x.key,JSON.stringify(x.draft))',{'key':draft_key,'draft':draft})
    refresh.reload();expect(refresh.locator('#connection')).to_have_text('Connected',timeout=30000)
    expect(refresh.locator('#target-label')).to_have_text(remote['label'])
    expect(refresh.locator('#target-dialog')).not_to_be_visible()
    expect(refresh.locator('#cards .card')).to_have_count(1)
    draft['designTarget']='dacp://removed.example:18861'
    refresh.evaluate('(x)=>localStorage.setItem(x.key,JSON.stringify(x.draft))',{'key':draft_key,'draft':draft})
    refresh.reload();expect(refresh.locator('#connection')).to_have_text('Connected',timeout=30000)
    expect(refresh.locator('#target-label')).to_have_text('Local')
    expect(refresh.locator('#target-dialog')).not_to_be_visible()
    expect(refresh.locator('#cards .card')).to_have_count(1)
    fresh_context.close()
    assert not errors,errors
    browser.close()
print('PASS: confirmation/cancel/Escape, browser isolation, Target import/export, silent refresh restoration and unavailable-target fallback and real read_item inference')
