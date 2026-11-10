#!/usr/bin/env python3
"""Audit all public public Galaxy manifests, source archives and runnable artifacts."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen
import zipfile
import yaml

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8820')
    parser.add_argument('--verification-directory', type=Path, help='Private publisher output parent; match passed artifact tests by SHA-256.')
    parser.add_argument('--report', type=Path, default=HERE / 'verification-public-catalog.json')
    parser.add_argument('--expected-count', type=int, default=200)
    args = parser.parse_args()
    def fetch(path):
        with urlopen(args.url.rstrip('/') + path, timeout=60) as response:
            return response.read()
    def blob(ref):
        data = fetch('/v1/blobs/sha256/' + ref['sha256'] + '/' + quote(ref['filename']))
        assert len(data) == ref['size'], ref
        assert hashlib.sha256(data).hexdigest() == ref['sha256'], ref
        return data
    specs = json.loads((HERE / 'catalog.json').read_text())
    assert len(specs) == args.expected_count
    public = json.loads(fetch('/v1/operators'))
    galaxy = {x['name'] for x in public if x['name'].startswith('galaxy_')}
    assert len(galaxy) == args.expected_count, ('public distinct count', len(galaxy))
    tests = {}
    if args.verification_directory:
        for path in args.verification_directory.glob('*/*-test.json'):
            result = json.loads(path.read_text())
            if result.get('status') == 'passed':
                tests[result['artifact_sha256']] = result
    results = []
    for name, spec in specs.items():
        manifest = json.loads(fetch('/v1/operators/' + spec['name'] + '/' + spec['version']))
        assert manifest['name'] == spec['name'] and manifest['version'] == spec['version']
        assert manifest['execution']['executor'] == 'jvm'
        assert manifest['source_public'] is True
        assert 'Galaxy' in manifest['categories']
        assert manifest['provenance']['source_revision']
        doc = manifest['documentation']
        assert set(doc['inputs']) == set(manifest['inputs'])
        assert doc['outputs']['result'].strip()
        assert set(doc['parameters']) == set(manifest['parameters']['properties'])
        for param in doc['parameters'].values():
            assert param['description'].strip() and param['help'].strip() and 'example' in param
        example = yaml.safe_load(doc['example_job'])
        assert any(s['operator'] == spec['name'] + '@' + spec['version'] for s in example['steps'])
        artifact = blob(manifest['artifact'])
        with zipfile.ZipFile(io.BytesIO(artifact)) as jar:
            entries = set(jar.namelist())
            assert manifest['execution']['entrypoint'].replace('.', '/') + '.class' in entries
            assert {'META-INF/LICENSE', 'META-INF/NOTICE', 'META-INF/galaxy-operator.json'} <= entries
        source = blob(manifest['provenance']['source_archive'])
        with zipfile.ZipFile(io.BytesIO(source)) as archive:
            entries = set(archive.namelist())
            assert {'LICENSE', 'NOTICE', 'README.md', 'README_zh.md'} <= entries
            assert any(n.startswith('src/') and n.endswith('.scala') for n in entries)
            assert any(n.startswith('upstream/') for n in entries)
            evidence = json.loads(archive.read('upstream-manifest.json'))
            for path, digest in evidence['files'].items():
                assert hashlib.sha256(archive.read('upstream/' + path)).hexdigest() == digest
            if 'upstream-extended-manifest.json' in entries:
                for family, evidence in json.loads(archive.read('upstream-extended-manifest.json')).items():
                    for path, digest in evidence['files'].items():
                        assert hashlib.sha256(archive.read('upstream-extended/' + family + '/' + path)).hexdigest() == digest
            if 'upstream-dependencies-manifest.json' in entries:
                for family, evidence in json.loads(archive.read('upstream-dependencies-manifest.json')).items():
                    for path, digest in evidence['files'].items():
                        assert hashlib.sha256(archive.read('upstream-dependencies/' + family + '/' + path)).hexdigest() == digest
        if args.verification_directory:
            assert manifest['artifact']['sha256'] in tests, ('missing artifact tests', spec['name'])
        results.append({'operator': spec['name'] + '@' + spec['version'],
                        'artifact_sha256': manifest['artifact']['sha256'],
                        'source_sha256': manifest['provenance']['source_archive']['sha256'],
                        'runtime': manifest['runtime']['image'], 'status': 'passed',
                        'artifact_tests': tests.get(manifest['artifact']['sha256'])})
        print('PASS public manifest, documentation, source and artifact', spec['name'], flush=True)
    args.report.write_text(json.dumps({'status': 'passed', 'distinct_operators': len(galaxy),
                                      'operators': results}, indent=2) + '\n')


if __name__ == '__main__':
    main()
