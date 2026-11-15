#!/usr/bin/env python3
"""Submit versioned transport programs using the repository build and review APIs."""
import argparse
import copy
import hashlib
import io
import json
import time
import zipfile
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--token-file', type=Path, required=True)
    parser.add_argument('--approve-password-file', type=Path)
    parser.add_argument('--runtime', default='stream-endpoints@1.0')
    args = parser.parse_args()
    token = args.token_file.read_text().strip()
    cookie = csrf = ''

    def api(path, method='GET', value=None, raw=None, admin=False):
        headers = {'Content-Type': 'application/octet-stream' if raw is not None else 'application/json'}
        headers.update({'Cookie': cookie, 'X-CSRF-Token': csrf} if admin else {'Authorization': 'Bearer ' + token})
        body = raw if raw is not None else None if value is None else json.dumps(value).encode()
        try:
            with urlopen(Request(args.url.rstrip('/') + path, body, headers, method=method), timeout=180) as response:
                return json.load(response), dict(response.headers)
        except HTTPError as error:
            raise RuntimeError(f'{method} {path}: HTTP {error.code}: {error.read(4096).decode()}') from None

    if args.approve_password_file:
        response, headers = api('/api/admin/login', 'POST', {'password': args.approve_password_file.read_text().strip()})
        cookie = next(v for k, v in headers.items() if k.lower() == 'set-cookie').split(';')[0]
        csrf = response['csrf']
    name, version = args.runtime.split('@', 1)
    runtimes = api('/v1/runtimes')[0]
    runtime = next((r for r in runtimes if (r['name'], r['version']) == (name, version)), None)
    if runtime is None:
        if not cookie:
            raise ValueError('Register the stream endpoint runtime as administrator first')
        base = next(r for r in runtimes if 'scala' in r['build']['languages'])
        runtime = copy.deepcopy(base)
        runtime.update(name=name, version=version, description='Verified declarative stream programs using host catalog and client transport services.', executors=['stream'], dependencies={})
        runtime['build']['languages'] = ['stream']
        api('/api/admin/runtimes', 'POST', runtime, admin=True)

    example = '''name: Return catalog Item
steps:
  - id: read
    operator: item_source@1.0
    inputs: {}
    params: {uri: 'context:/example/input.csv', view: records}
  - id: receive
    operator: client_sink@1.0
    inputs: {result: read:result}
'''
    properties = {
        'uri': {'type': 'string', 'minLength': 1, 'description': 'Catalog Item URI: context:/dataset/item or a configured remote URI.'},
        'view': {'type': 'string', 'minLength': 1, 'description': 'An advertised Item View; omitted to select records when available, then bytes.'},
        'sql': {'type': 'string', 'minLength': 1, 'description': 'Optional SELECT query over the records view, using FROM source.'},
        'selection': {'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': True, 'description': 'View-specific subset parameters advertised by the selected View.'},
    }
    examples = {'uri': 'context:/example/input.csv', 'view': 'records', 'sql': 'SELECT * FROM source LIMIT 10', 'selection': {}}
    for operator, operation, role in [('item_source', 'catalog.read', 'source'), ('client_sink', 'client.receive', 'sink')]:
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('endpoint.json', (HERE / (operator + '.json')).read_bytes())
            for filename in ('README.md', 'README_zh.md'):
                archive.writestr(filename, (HERE / filename).read_bytes())
        source = output.getvalue()
        digest = hashlib.sha256(source).hexdigest()
        api('/v1/blobs/sha256/' + digest, 'PUT', raw=source)
        inputs = {} if role == 'source' else {'result': {'representations': ['variable'], 'formats': []}}
        params = properties if role == 'source' else {}
        manifest = {
            'api_version': 2, 'name': operator, 'version': '1.0', 'role': role, 'type': 'sdk',
            'description': 'Read a selected catalog Item View as a stream.' if role == 'source' else 'Return named result streams to the Job caller without publishing a dataset.',
            'icon': {'path': 'M5 6h14v5H5zM12 11v9m-4-4 4 4 4-4' if role == 'source' else 'M4 4h16v12H4zM8 20h8M12 16v4', 'color': '#2378cb' if role == 'source' else '#a56d1d'},
            'inputs': inputs, 'outputs': {'result': {'view': 'variable', 'view_from': {'parameter': 'view'}}} if role == 'source' else {}, 'parameters': {'type': 'object', 'properties': params, 'required': ['uri'] if role == 'source' else [], 'additionalProperties': False},
            'execution': {'executor': 'stream', 'entrypoint': operation, 'config': {}},
            'runtime': {'image': runtime['image'], 'archive': runtime['archive']},
            'build': {'language': 'stream'}, 'source_public': True,
            'provenance': {'source_archive': {'sha256': digest, 'size': len(source), 'filename': operator + '-sources.zip'}, 'license': 'Apache-2.0'},
            'documentation': {
                'inputs': {} if role == 'source' else {'result': 'Stream returned under this result label; additional named result bindings are supported by the client receiver transport.'},
                'outputs': {'result': 'The selected Item View with its original Arrow representation.'} if role == 'source' else {},
                'parameters': {key: {'description': value['description'], 'help': value['description'], 'example': examples[key]} for key, value in params.items()},
                'example_job': example,
            },
        }
        result = api('/v1/submissions', 'POST', manifest)[0]
        sid = result['submission_id']
        print(json.dumps({'operator': operator + '@1.0', 'submission': sid, 'state': 'submitted'}), flush=True)
        if not cookie:
            continue
        deadline = time.monotonic() + 180
        while True:
            submission = api('/api/admin/submissions/' + sid, admin=True)[0]
            status = submission['build_result']['status']
            if status in ('succeeded', 'failed'):
                break
            if time.monotonic() > deadline:
                raise TimeoutError(sid)
            time.sleep(1)
        if status != 'succeeded':
            raise RuntimeError(submission['build_result'])
        api('/api/admin/submissions/' + sid + '/review', 'POST', {'decision': 'approved', 'categories': ['Streams', role.capitalize()], 'trust_score': 5, 'note': 'Reviewed declarative transport program; host services retain authorization, namespace validation, stream cleanup and cancellation.'}, admin=True)
        print(json.dumps({'operator': operator + '@1.0', 'state': 'published'}), flush=True)
    if cookie:
        api('/api/admin/logout', 'POST', {}, admin=True)


if __name__ == '__main__':
    main()
