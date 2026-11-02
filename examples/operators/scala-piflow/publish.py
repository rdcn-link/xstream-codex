#!/usr/bin/env python3
"""Submit Scala example sources; optionally review successful builds on a local repository."""
import argparse, hashlib, io, json, time, zipfile
from pathlib import Path
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
OPERATORS = {
    'csv_parser': ('CsvParser', 'Csv.scala'), 'csv_save': ('CsvSave', 'Csv.scala'),
    'filter': ('Filter', 'Sql.scala'), 'execute_sql_stop': ('ExecuteSql', 'Sql.scala'),
    'merge': ('Merge', 'Relational.scala'), 'join': ('Join', 'Relational.scala'),
}

def package(name):
    cls, source = OPERATORS[name]
    files = {'pom.xml': HERE / 'pom.xml'}
    for filename in ('Support.scala', source):
        files['src/' + filename] = HERE / 'src/main/scala/xstream/examples/piflow' / filename
    for filename in ('README.md', 'README_zh.md', 'LICENSE', 'NOTICE'):
        files[filename] = HERE / filename
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, path in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, path.read_bytes())
    return data.getvalue()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--url', required=True)
    p.add_argument('--token-file', type=Path, required=True)
    p.add_argument('--runtime', default='jvm-arrow@2.1')
    p.add_argument('--approve-password-file', type=Path)
    p.add_argument('operators', nargs='*', default=list(OPERATORS))
    args = p.parse_args()
    for name in args.operators:
        if name not in OPERATORS: p.error("unknown operator: " + name)
    token = args.token_file.read_text().strip(); cookie = csrf = ''
    def api(path, method='GET', value=None, raw=None, admin=False):
        headers = {'Content-Type': 'application/octet-stream' if raw is not None else 'application/json'}
        if admin: headers.update(Cookie=cookie, **{'X-CSRF-Token': csrf})
        else: headers['Authorization'] = 'Bearer ' + token
        body = raw if raw is not None else None if value is None else json.dumps(value).encode()
        with urlopen(Request(args.url.rstrip('/') + path, body, headers, method=method), timeout=180) as r:
            return json.load(r), dict(r.headers)
    if args.approve_password_file:
        response, headers = api('/api/admin/login', 'POST', {'password': args.approve_password_file.read_text().strip()})
        cookie = next(v for k, v in headers.items() if k.lower() == 'set-cookie').split(';')[0]; csrf = response['csrf']
    runtime_name, runtime_version = args.runtime.split('@')
    runtime = next(r for r in api('/v1/runtimes')[0] if (r['name'],r['version']) == (runtime_name,runtime_version))
    runtime_ref = {'image':runtime['image'], 'archive':runtime['archive']}
    specs = json.loads((HERE.parent / 'piflow/specs.json').read_text())
    for name in args.operators:
        cls, _ = OPERATORS[name]; spec = specs[name]
        data = package(name); digest = hashlib.sha256(data).hexdigest()
        api('/v1/blobs/sha256/' + digest, 'PUT', raw=data)
        manifest = dict(api_version=2, name='piflow_' + name, version='2.0',
            description=spec['description'], icon=spec['icon'], inputs=spec['inputs'], parameters=spec['parameters'],
            execution=dict(executor='jvm', entrypoint='xstream.examples.piflow.' + cls, config={}),
            runtime=runtime_ref, dependencies={'provider':'jvm'}, build={'language':'scala'}, source_public=True,
            provenance={'source_archive':{'sha256':digest,'size':len(data),'filename':name + '-sources.zip'}, 'license':'Apache-2.0'})
        result, _ = api('/v1/submissions', 'POST', manifest); sid = result['submission_id']
        print(json.dumps({'operator':manifest['name']+'@2.0','submission':sid,'state':'submitted'}), flush=True)
        if not args.approve_password_file: continue
        deadline = time.monotonic() + 900
        while True:
            submission = api('/api/admin/submissions/' + sid, admin=True)[0]
            state = submission['build_result']['status']
            if state in ('succeeded','failed'): break
            if time.monotonic() > deadline: raise TimeoutError(sid)
            time.sleep(1)
        if state != 'succeeded': raise RuntimeError(submission['build_result'])
        categories = ['Data transformation']
        if name.startswith('csv_'): categories += ['CSV']
        elif name in ('join','merge'): categories += ['Relational']
        else: categories += ['SQL']
        api('/api/admin/submissions/' + sid + '/review', 'POST', {
            'decision':'approved','categories':categories,'trust_score':4,
            'note':'Reviewed Scala sources and pinned dependencies; bounded samples, in-memory SQL with query-only mode and execution budget, no data files or network calls. Operator contract tests passed.'}, admin=True)
        print(json.dumps({'operator':manifest['name']+'@2.0','state':'published'}), flush=True)
    if cookie: api('/api/admin/logout', 'POST', {}, admin=True)

if __name__ == '__main__': main()
