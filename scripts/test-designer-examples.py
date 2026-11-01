#!/usr/bin/env python3
"""Exercise packaged example loading, replacement protection, variables and Undo."""
import argparse,re
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright,expect
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',default='http://localhost:8840');p.add_argument('--chromium',required=True);a=p.parse_args()
with sync_playwright() as playwright:
    browser=playwright.chromium.launch(executable_path=a.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
    page=browser.new_page(viewport={'width':1600,'height':1000});page.add_init_script('localStorage.clear()');errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto(a.url);expect(page.locator('#repository-state')).to_have_text(re.compile(r'\d+ operators'),timeout=30000)
    page.locator('#library-examples-tab').click();expect(page.locator('.example-job')).to_have_count(10)
    expect(page.locator('.example-number')).to_have_text([str(i).zfill(2) for i in range(1,11)])
    expect(page.locator('[data-example="three-nodes"] .example-tags')).to_contain_text('Cross-node')
    expect(page.locator('[data-example="variables"] .example-tags')).to_contain_text('Variables')
    expect(page.locator('[data-example="remote-target"] .example-tags')).to_contain_text('Remote target')
    page.locator('[data-example="read-records"] button').click();expect(page.locator('#job-name')).to_have_value('Read weather observations');expect(page.locator('#cards .card')).to_have_count(1)
    page.once('dialog',lambda d:d.dismiss());page.locator('[data-example="variables"] button').click();expect(page.locator('#job-name')).to_have_value('Read weather observations')
    page.once('dialog',lambda d:d.accept());page.locator('[data-example="variables"] button').click();expect(page.locator('#job-name')).to_have_value('Parameterized weather screening');expect(page.locator('#cards .card')).to_have_count(3)
    assert 'INCLUDE_HEADER: true' in page.evaluate('variablesEditor.getText()')
    page.locator('#undo').click();expect(page.locator('#job-name')).to_have_value('Read weather observations');expect(page.locator('#cards .card')).to_have_count(1);assert page.evaluate('variablesEditor.getText().trim()')=='{}'
    page.once('dialog',lambda d:d.accept());page.locator('[data-example="merge"] button').click();expect(page.locator('#cards .card')).to_have_count(4)
    assert page.evaluate("Object.keys(job.steps.find(s=>s.id==='merge').inputs).sort().join(',')")=='left,right'
    for example_id, count in [('two-nodes',4),('three-nodes',6)]:
        page.once('dialog',lambda d:d.accept());page.locator('[data-example="'+example_id+'"] button').click();expect(page.locator('#job-name')).to_have_value('Merge observations from '+('two' if example_id=='two-nodes' else 'three')+' remote nodes');expect(page.locator('#cards .card')).to_have_count(count)
        assert page.evaluate("job.steps.filter(s=>s.operator==='read_item@1.0').every(s=>s.params.uri.startsWith('$NODE_'))")
    example_docs={e['id']:e for e in page.request.post(a.url+'/api/examples',data={}).json()}
    for example_id in ['remote-target','union-collaboration']:
        target=re.search(r'^  target: (\S+)',example_docs[example_id]['job_yaml'],re.M).group(1);address=urlparse(target).hostname
        page.once('dialog',lambda d:d.accept());page.locator('[data-example="'+example_id+'"] button').click()
        expect(page.locator('#target-dialog')).to_be_visible();expect(page.locator('#target-message')).to_contain_text(address)
        page.locator('#target-cancel').click();expect(page.locator('#target-dialog')).not_to_be_visible()
        expect(page.locator('#cards .card')).to_have_count(1 if example_id=='remote-target' else 4)
    page.locator('#library-examples-tab').focus();page.keyboard.press('Home');expect(page.locator('#library-operators-tab')).to_be_focused();page.keyboard.press('End');expect(page.locator('#library-examples-tab')).to_be_focused()
    assert not errors,errors;browser.close()
print('PASS: Examples tab, replacement cancel/confirm, typed variables, Undo, multi-input graphs, cross-node examples, saved Target confirmation and keyboard navigation')
