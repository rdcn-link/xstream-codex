#!/usr/bin/env python3
"""Browser checks for repository trust icons and multiline step descriptions."""
import argparse
import json
import copy
from playwright.sync_api import sync_playwright, expect

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', default='http://localhost:8840')
parser.add_argument('--chromium', required=True)
args = parser.parse_args()
with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path=args.chromium, args=['--no-sandbox', '--disable-dev-shm-usage'])
    page = browser.new_page(viewport={'width': 1440, 'height': 1000})
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.add_init_script('localStorage.clear()')
    def operators(route):
        data = route.fetch().json()
        scores = {'script_text_replace': 0, 'script_line_number': 3, 'script_records_to_jsonl': 5, 'script_jsonl_project': None}
        for o in data:
            if o['name'] in scores:
                if scores[o['name']] is None:
                    o.pop('trust', None)
                else:
                    o['trust'] = {'score': scores[o['name']]}
        template=next(o for o in data if o['name']=='script_line_number')
        for version in ['1.9', '1.10-rc.1', '1.10']:
            old=copy.deepcopy(template);old.update(name='version_test',version=version,categories=['Version test']);data.append(old)
        route.fulfill(json=data)
    page.route('**/api/operators', operators)
    page.route('**/api/infer*', lambda r: r.fulfill(status=503, json={'error': 'Inference disabled during metadata UI check'}))
    page.goto(args.url)
    line = page.locator('.operator[data-operator="script_line_number@1.0"]').first
    expect(line).to_be_visible()
    for name, score in [('script_text_replace', 0), ('script_line_number', 3), ('script_records_to_jsonl', 5)]:
        icon = page.locator('.operator[data-operator="'+name+'@1.0"] .operator-rating').first
        expect(icon.locator('svg.trust-icon')).to_have_count(5)
        expect(icon.locator('.trust-check')).to_have_count(5)
        expect(icon.locator('.rated')).to_have_count(score)
        expect(icon.locator('.unrated')).to_have_count(5-score)
        expect(icon).to_have_attribute('aria-label', 'Repository administrator trust: '+str(score)+' / 5')
    expect(page.locator('.operator[data-operator="script_jsonl_project@1.0"] .operator-rating').first).to_have_text('?')
    expect(page.locator('.operator[data-operator="read_item@1.0"] .operator-rating')).to_have_text('Built-in')
    expect(page.locator('.operator[data-operator="version_test@1.10"]')).to_have_count(1)
    expect(page.locator('.operator[data-operator="version_test@1.9"]')).to_have_count(0)
    expect(page.locator('.operator[data-operator="version_test@1.10-rc.1"]')).to_have_count(0)
    line.dblclick()
    card = page.locator('#cards .card').last
    expect(card.locator('.operator-rating .rated')).to_have_count(3)
    card.dblclick()
    description = 'Read local observations.\nPreserve source units.\n<plain text, not HTML>'
    page.locator('#step-description').fill(description)
    expect(page.locator('#step-save-status')).to_have_text('')
    assert page.evaluate('job.steps[0].description') == description
    page.wait_for_function('() => jobYamlEditor.getText().includes("Preserve source units.")')
    count = page.locator('#cards .card').count()
    page.locator('#duplicate-step').click()
    expect(page.locator('#cards .card')).to_have_count(count+1)
    assert page.evaluate('job.steps.at(-1).description') == description
    page.locator('#undo').click()
    expect(page.locator('#cards .card')).to_have_count(count)
    page.locator('#redo').click()
    expect(page.locator('#cards .card')).to_have_count(count+1)
    exported = page.evaluate('payload(false)')
    assert json.loads(exported['job_yaml'])['steps'][0]['description'] == description
    page.evaluate('async p => { await loadDocument(p.job_yaml,p.variables_yaml); }', exported)
    assert page.evaluate('job.steps[0].description') == description
    expect(page.locator('#cards .card').first.locator('.operator-rating .rated')).to_have_count(3)
    legacy=json.loads(exported['job_yaml']);legacy['steps'][0]['operator']='version_test@1.9'
    page.evaluate('async p => { await loadDocument(JSON.stringify(p)); }',legacy)
    assert page.evaluate('manifest(job.steps[0]).version')=='1.9'
    assert page.evaluate('job.steps[0].description')==description
    assert not errors, errors
    browser.close()
print('PASS trust 0/3/5/unknown/built-in, canvas badges, latest-version library, preserved historical versions, multiline description, duplicate, undo/redo and YAML reload')
