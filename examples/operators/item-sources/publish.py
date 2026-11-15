#!/usr/bin/env python3
"""Submit and optionally approve Item collection Source and Transform operators."""
import argparse
import hashlib
import io
import json
import time
import zipfile
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

HERE = Path(__file__).resolve().parent
SPECS = {'folder_items_source': {'class': 'FolderItemsSource',
                         'role': 'source',
                         'description': 'Enumerate a logical folder as lazy Item references.',
                         'inputs': {},
                         'outputs': {'result': 'Ordered Item references with metadata.'},
                         'properties': {'uri': {'type': 'string',
                                                'minLength': 1,
                                                'description': 'Folder Item URI.'},
                                        'recursive': {'type': 'boolean',
                                                      'default': False,
                                                      'description': 'Include descendants; directory '
                                                                     'references are retained.'}},
                         'required': ['uri'],
                         'examples': {'uri': ('Use a context or configured remote Item URI.',
                                              'context:/science/labels'),
                                      'recursive': ('False lists direct children only.', True)},
                         'job': 'name: Item collection example\n'
                                'steps:\n'
                                '- id: list\n'
                                '  operator: folder_items_source@1.0\n'
                                '  inputs: {}\n'
                                '  params: {"uri": "context:/science/labels", "recursive": true}\n'
                                '- id: inspect\n'
                                '  operator: item_inventory@1.0\n'
                                '  inputs: {source: list:result}\n'
                                '  params: {}\n'
                                '- id: receive\n'
                                '  operator: client_sink@1.0\n'
                                '  inputs: {result: inspect:result}\n',
                         'icon': 'M3 6h7l2 2h9v12H3z',
                         'color': '#287da2',
                         'categories': ['Source', 'Items']},
 'csv_items_source': {'class': 'CsvItemsSource',
                      'role': 'source',
                      'description': 'Read a CSV manifest into ordered lazy Item references.',
                      'inputs': {},
                      'outputs': {'result': 'Ordered Item references with metadata.'},
                      'properties': {'uri': {'type': 'string',
                                             'minLength': 1,
                                             'description': 'CSV manifest Item URI.'},
                                     'uri_column': {'type': 'string',
                                                    'minLength': 1,
                                                    'default': 'uri',
                                                    'description': 'String column containing one Item URI or '
                                                                   'logical path per row.'}},
                      'required': ['uri'],
                      'examples': {'uri': ('Relative member paths resolve against this manifest.',
                                           'context:/science/manifest.csv'),
                                   'uri_column': ('Other columns are retained as member metadata.', 'uri')},
                      'job': 'name: Item collection example\n'
                             'steps:\n'
                             '- id: list\n'
                             '  operator: csv_items_source@1.0\n'
                             '  inputs: {}\n'
                             '  params: {"uri": "context:/science/manifest.csv", "uri_column": "uri"}\n'
                             '- id: inspect\n'
                             '  operator: item_inventory@1.0\n'
                             '  inputs: {source: list:result}\n'
                             '  params: {}\n'
                             '- id: receive\n'
                             '  operator: client_sink@1.0\n'
                             '  inputs: {result: inspect:result}\n',
                      'icon': 'M3 6h7l2 2h9v12H3z',
                      'color': '#287da2',
                      'categories': ['Source', 'Items']},
 'item_inventory': {'class': 'ItemInventory',
                    'role': 'transform',
                    'description': 'List Item URI, kind and format without opening member contents.',
                    'inputs': {'source': {'representations': ['items'], 'formats': []}},
                    'outputs': {'result': 'UTF-8 tab-separated report.'},
                    'properties': {},
                    'required': [],
                    'examples': {},
                    'job': 'name: Item collection example\n'
                           'steps:\n'
                           '- id: list\n'
                           '  operator: folder_items_source@1.0\n'
                           '  inputs: {}\n'
                           '  params: {"uri": "context:/science/labels"}\n'
                           '- id: inspect\n'
                           '  operator: item_inventory@1.0\n'
                           '  inputs: {source: list:result}\n'
                           '  params: {}\n'
                           '- id: receive\n'
                           '  operator: client_sink@1.0\n'
                           '  inputs: {result: inspect:result}\n',
                    'icon': 'M5 3h14v18H5zM8 8h8M8 12h8M8 16h5',
                    'color': '#23836d',
                    'categories': ['Transform', 'Items']},
 'item_byte_count': {'class': 'ItemByteCount',
                     'role': 'transform',
                     'description': 'Open Item byte views on demand and report each URI and its byte count.',
                     'inputs': {'source': {'representations': ['items'], 'formats': []}},
                     'outputs': {'result': 'UTF-8 tab-separated report.'},
                     'properties': {},
                     'required': [],
                     'examples': {},
                     'job': 'name: Item collection example\n'
                            'steps:\n'
                            '- id: list\n'
                            '  operator: folder_items_source@1.0\n'
                            '  inputs: {}\n'
                            '  params: {"uri": "context:/science/labels"}\n'
                            '- id: inspect\n'
                            '  operator: item_byte_count@1.0\n'
                            '  inputs: {source: list:result}\n'
                            '  params: {}\n'
                            '- id: receive\n'
                            '  operator: client_sink@1.0\n'
                            '  inputs: {result: inspect:result}\n',
                     'icon': 'M5 3h14v18H5zM8 8h8M8 12h8M8 16h5',
                     'color': '#23836d',
                     'categories': ['Transform', 'Items']}}

def archive():
    files = {
        'src/ItemSources.scala': HERE / 'src/main/scala/xstream/examples/items/ItemSources.scala',
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
    parser.add_argument('--runtime', default='jvm-items@1.0')
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
        try:
            with urlopen(Request(args.url.rstrip('/') + path, body, headers, method=method), timeout=180) as response:
                return json.load(response), dict(response.headers)
        except HTTPError as error:
            raise RuntimeError(f'{method} {path}: HTTP {error.code}: {error.read(4096).decode()}') from None
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
            'inputs': {port: 'Ordered Item references; member content is opened only when explicitly requested.' for port in spec['inputs']},
            'outputs': spec['outputs'],
            'parameters': {key: {'description': value['description'], 'help': spec['examples'][key][0], 'example': spec['examples'][key][1]} for key, value in spec['properties'].items()},
            'example_job': spec['job'],
        }
        manifest = {
            'api_version': 2, 'name': name, 'version': '1.0', 'role': spec['role'], 'type': 'sdk',
            'description': spec['description'], 'icon': {'path': spec['icon'], 'color': spec['color']},
            'inputs': spec['inputs'], 'outputs': {port: {'view': 'items' if spec['role'] == 'source' else 'bytes'} for port in spec['outputs']}, 'parameters': {'type': 'object', 'properties': spec['properties'], 'required': spec['required'], 'additionalProperties': False},
            'documentation': documentation, 'execution': {'executor': 'jvm', 'entrypoint': 'xstream.examples.items.' + spec['class'], 'config': {}},
            'runtime': {'image': runtime['image'], 'archive': runtime['archive']}, 'dependencies': {'provider': 'jvm'},
            'build': {'language': 'scala'}, 'source_public': True,
            'provenance': {'source_archive': {'sha256': digest, 'size': len(source), 'filename': 'item-sources.zip'}, 'license': 'Apache-2.0'},
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
            'note': 'Reviewed Scala Item collection contracts and host-mediated lazy reads; operators do not access arbitrary host paths or credentials.'
        }, admin=True)
        print(json.dumps({'operator': name + '@1.0', 'state': 'published'}), flush=True)
    if cookie: api('/api/admin/logout', 'POST', {}, admin=True)

if __name__ == '__main__': main()
