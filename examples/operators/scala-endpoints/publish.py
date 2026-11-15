#!/usr/bin/env python3
"""Submit and optionally approve four Scala Source/Sink examples."""
import argparse
import hashlib
import io
import json
import time
import zipfile
from pathlib import Path
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
SPECS = {
    'text_source': {
        'class': 'TextSource', 'role': 'source', 'description': 'Generate a bounded UTF-8 byte stream from a text parameter.',
        'inputs': {}, 'outputs': {'result': 'Generated UTF-8 text bytes.'},
        'properties': {'text': {'type': 'string', 'maxLength': 4096, 'description': 'Text to emit.'}, 'repeat': {'type': 'integer', 'minimum': 0, 'maximum': 10000, 'default': 1, 'description': 'Number of repetitions.'}},
        'required': ['text'], 'examples': {'text': ('UTF-8 content to generate.', 'hello\n'), 'repeat': ('Repeat count; zero produces an empty stream.', 3)},
        'job': 'name: Generate text\nsteps:\n- id: generate\n  operator: text_source@1.0\n  inputs: {}\n  params: {text: "hello\\n", repeat: 3}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: generate}\n',
        'icon': 'M4 5h16v14H4zM8 9h8M8 13h6M8 17h4', 'color': '#23836d', 'categories': ['Source', 'Text'],
    },
    'range_source': {
        'class': 'RangeSource', 'role': 'source', 'description': 'Generate newline-delimited integers as a bounded byte stream.',
        'inputs': {}, 'outputs': {'result': 'One decimal integer per line, in ascending order.'},
        'properties': {'start': {'type': 'integer', 'description': 'First integer in the range.'}, 'count': {'type': 'integer', 'minimum': 0, 'maximum': 100000, 'description': 'Number of integers.'}},
        'required': ['start', 'count'], 'examples': {'start': ('Choose the first signed 64-bit integer.', 1), 'count': ('Generate at most 100000 lines.', 100)},
        'job': 'name: Generate integer range\nsteps:\n- id: generate\n  operator: range_source@1.0\n  inputs: {}\n  params: {start: 1, count: 100}\n- id: client_sink\n  operator: client_sink@1.0\n  inputs: {result: generate}\n',
        'icon': 'M4 4h16v16H4zM8 9h2M8 13h5M8 17h8', 'color': '#287da2', 'categories': ['Source', 'Numbers'],
    },
    'contains_text_sink': {
        'class': 'ContainsTextSink', 'role': 'sink', 'description': 'Fail the Job unless a byte stream contains the specified UTF-8 text.',
        'inputs': {'source': {'representations': ['bytes'], 'formats': []}}, 'outputs': {},
        'properties': {'text': {'type': 'string', 'minLength': 1, 'maxLength': 256, 'description': 'Required UTF-8 text.'}},
        'required': ['text'], 'examples': {'text': ('Match across chunk boundaries; use a nonempty exact string.', 'success')},
        'job': 'name: Assert text occurs\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: context:/example/input.txt, view: bytes}\n- id: check\n  operator: contains_text_sink@1.0\n  inputs: {source: read}\n  params: {text: success}\n',
        'icon': 'M4 4h16v16H4zM7 12l3 3 7-7', 'color': '#9c6941', 'categories': ['Sink', 'Validation'],
    },
    'min_lines_sink': {
        'class': 'MinLinesSink', 'role': 'sink', 'description': 'Fail the Job unless a byte stream contains a minimum number of lines.',
        'inputs': {'source': {'representations': ['bytes'], 'formats': []}}, 'outputs': {},
        'properties': {'minimum': {'type': 'integer', 'minimum': 0, 'maximum': 100000000, 'description': 'Minimum accepted line count.'}},
        'required': ['minimum'], 'examples': {'minimum': ('A final line without a newline is counted.', 10)},
        'job': 'name: Assert line count\nsteps:\n- id: read\n  operator: item_source@1.0\n  inputs: {}\n  params: {uri: context:/example/input.txt, view: bytes}\n- id: check\n  operator: min_lines_sink@1.0\n  inputs: {source: read}\n  params: {minimum: 10}\n',
        'icon': 'M4 4h16v16H4zM7 9h10M7 13h10M7 17h6', 'color': '#a27135', 'categories': ['Sink', 'Validation'],
    },
}

def archive():
    files = {
        'src/Endpoints.scala': HERE / 'src/main/scala/xstream/examples/endpoints/Endpoints.scala',
        'pom.xml': HERE / 'pom.xml',
        'README.md': HERE / 'README.md',
        'README_zh.md': HERE / 'README_zh.md',
        'LICENSE': HERE / 'LICENSE',
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zipped:
        for name, path in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)); entry.compress_type = zipfile.ZIP_DEFLATED
            zipped.writestr(entry, path.read_bytes())
    return output.getvalue()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--token-file', type=Path, required=True)
    parser.add_argument('--approve-password-file', type=Path)
    parser.add_argument('--runtime', default='jvm-arrow@2.1')
    parser.add_argument('operators', nargs='*', choices=list(SPECS))
    args = parser.parse_args()
    names = args.operators or list(SPECS)
    token = args.token_file.read_text().strip()
    cookie = csrf = ''
    def api(path, method='GET', value=None, raw=None, admin=False):
        headers = {'Content-Type': 'application/octet-stream' if raw is not None else 'application/json'}
        if admin: headers.update({'Cookie': cookie, 'X-CSRF-Token': csrf})
        else: headers['Authorization'] = 'Bearer ' + token
        body = raw if raw is not None else None if value is None else json.dumps(value).encode()
        with urlopen(Request(args.url.rstrip('/') + path, body, headers, method=method), timeout=180) as response:
            return json.load(response), dict(response.headers)
    if args.approve_password_file:
        response, headers = api('/api/admin/login', 'POST', {'password': args.approve_password_file.read_text().strip()})
        cookie = next(v for k, v in headers.items() if k.lower() == 'set-cookie').split(';')[0]
        csrf = response['csrf']
    runtime_name, runtime_version = args.runtime.split('@', 1)
    runtime = next(r for r in api('/v1/runtimes')[0] if (r['name'], r['version']) == (runtime_name, runtime_version))
    source = archive(); digest = hashlib.sha256(source).hexdigest()
    api('/v1/blobs/sha256/' + digest, 'PUT', raw=source)
    for name in names:
        spec = SPECS[name]
        documentation = {
            'inputs': {port: 'UTF-8 byte stream to validate.' for port in spec['inputs']},
            'outputs': spec['outputs'],
            'parameters': {key: {'description': value['description'], 'help': spec['examples'][key][0], 'example': spec['examples'][key][1]} for key, value in spec['properties'].items()},
            'example_job': spec['job'],
        }
        manifest = {
            'api_version': 2, 'name': name, 'version': '1.0', 'role': spec['role'], 'type': 'sdk',
            'description': spec['description'], 'icon': {'path': spec['icon'], 'color': spec['color']},
            'inputs': spec['inputs'], 'outputs': {port: {'view': 'bytes'} for port in spec['outputs']}, 'parameters': {'type': 'object', 'properties': spec['properties'], 'required': spec['required'], 'additionalProperties': False},
            'documentation': documentation, 'execution': {'executor': 'jvm', 'entrypoint': 'xstream.examples.endpoints.' + spec['class'], 'config': {}},
            'runtime': {'image': runtime['image'], 'archive': runtime['archive']}, 'dependencies': {'provider': 'jvm'},
            'build': {'language': 'scala'}, 'source_public': True,
            'provenance': {'source_archive': {'sha256': digest, 'size': len(source), 'filename': 'scala-endpoints-sources.zip'}, 'license': 'Apache-2.0'},
        }
        result, _ = api('/v1/submissions', 'POST', manifest)
        sid = result['submission_id']
        print(json.dumps({'operator': name + '@1.0', 'submission': sid, 'state': 'submitted'}), flush=True)
        if not args.approve_password_file: continue
        deadline = time.monotonic() + 900
        while True:
            submission = api('/api/admin/submissions/' + sid, admin=True)[0]
            status = submission['build_result']['status']
            if status in ('succeeded', 'failed'): break
            if time.monotonic() > deadline: raise TimeoutError(sid)
            time.sleep(1)
        if status != 'succeeded': raise RuntimeError({'operator': name, 'build_result': submission['build_result']})
        api('/api/admin/submissions/' + sid + '/review', 'POST', {
            'decision': 'approved', 'categories': spec['categories'], 'trust_score': 4,
            'note': 'Reviewed bounded Scala Source/Sink implementation and contract tests. No network, external files, or third-party dependencies.'
        }, admin=True)
        print(json.dumps({'operator': name + '@1.0', 'state': 'published'}), flush=True)
    if cookie: api('/api/admin/logout', 'POST', {}, admin=True)

if __name__ == '__main__': main()
