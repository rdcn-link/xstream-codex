"""Isolated Docker smoke for local inline operators; run with Docker access (sudo if needed)."""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--image', default='xstream/operator-python:1.0')
parser.add_argument('--jvm-jar', type=Path, help='Optional Scala SDK operator JAR for a JVM inline execution check')
parser.add_argument('--jvm-image', default='xstream/operator-jvm:1.1')
parser.add_argument('--jvm-entrypoint', default='dacp.common.Op_text_uppercase')
parser.add_argument('--runner', type=Path, default=Path(__file__).resolve().parents[1] / 'xstream-engine/target/universal/stage/bin/xstream-runner')
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='inline-job-smoke-') as directory:
    base = Path(directory)
    (base / 'sdk.py').write_text('''from xstream_operator import BytesOperator
class Upper(BytesOperator):
    def transform(self, chunk, params): return chunk.upper()
if __name__ == '__main__': Upper().main()
''')
    (base / 'file_script.py').write_text('''import pathlib,sys
pathlib.Path(sys.argv[2]).write_bytes(pathlib.Path(sys.argv[1]).read_bytes()+sys.argv[3].encode())
''')
    config = {'inline_runtimes': {'tools': {'image': args.image, 'executor': 'python'}},
              'operator_cache': str(base/'cache'), 'execution_directory': str(base/'history'),
              'operator_fusion_enabled': False, 'max_concurrent_tasks': 4}
    (base / 'engine.yaml').write_text(json.dumps(config))
    definitions = {
        'source': {'type': 'script', 'role': 'source', 'runtime': 'tools', 'interpreter': '/usr/local/bin/python',
                   'script': {'source': "import sys; sys.stdout.write('abc')"}, 'inputs': {},
                   'outputs': {'result': {'view': 'bytes', 'binding': 'stdout', 'format': 'text'}}},
        'sdk': {'type': 'sdk', 'role': 'transform', 'runtime': 'tools', 'program': {'path': './sdk.py'},
                'inputs': {'source': {'representations': ['bytes'], 'formats': []}}},
        'tool': {'type': 'command', 'role': 'transform', 'runtime': 'tools', 'command': '/usr/local/bin/python',
                 'args': ['-c', 'import sys; sys.stdout.buffer.write(sys.stdin.buffer.read().replace(b"A", b"Z"))'],
                 'inputs': {'source': {'view': 'bytes', 'binding': 'stdin'}},
                 'outputs': {'result': {'view': 'bytes', 'binding': 'stdout', 'format': 'text'}}},
        'files': {'type': 'script', 'role': 'transform', 'runtime': 'tools', 'interpreter': '/usr/local/bin/python',
                  'script': {'path': './file_script.py'},
                  'args': [{'input': 'source'}, {'output': 'result'}, {'param': 'suffix'}],
                  'inputs': {'source': {'view': 'bytes', 'binding': 'file'}},
                  'outputs': {'result': {'view': 'bytes', 'binding': 'file', 'format': 'text'}}},
    }
    job = {'name': 'Inline SDK + command + script', 'operators': definitions, 'steps': [
        {'id': name, 'operator': {'inline': name}, 'inputs': {} if index == 0 else {'source': ['source', 'sdk', 'tool'][index-1]},
         'params': {'suffix': '!'} if name == 'files' else {}}
        for index, name in enumerate(['source', 'sdk', 'tool', 'files'])] + [{'id': 'client_sink', 'operator': 'client_sink@1.0', 'inputs': {'result': 'files'}}]}
    (base/'job.yaml').write_text(json.dumps(job))
    # Deliberately run from another cwd: program paths must remain anchored to the Job file.
    result = subprocess.run([str(args.runner.resolve()), str(base/'engine.yaml'), str(base/'job.yaml')],
                            cwd='/', capture_output=True, timeout=120)
    assert result.returncode == 0 and result.stdout == b'ZBC!', result.stderr.decode()[-8000:]
    print('PASS: inline SDK, command, embedded/external scripts, streams, file bindings, dynamic parameters and Job-relative paths')
    definitions['source']['script']['source'] = 'import sys; sys.exit(7)'
    (base/'job.yaml').write_text(json.dumps(job))
    result = subprocess.run([str(args.runner.resolve()), str(base/'engine.yaml'), str(base/'job.yaml')],
                            cwd='/', capture_output=True, timeout=120)
    assert result.returncode != 0 and b'ZBC!' not in result.stdout, result.stderr.decode()[-8000:]
    print('PASS: nonzero tool exit fails the Job')
    if args.jvm_jar:
        jar = args.jvm_jar.resolve()
        assert jar.is_file(), jar
        config['inline_runtimes']['jvm'] = {'image': args.jvm_image, 'executor': 'jvm'}
        (base/'engine.yaml').write_text(json.dumps(config))
        jvm_job = {'name': 'Inline Scala SDK', 'operators': {
            'source': {'type': 'script', 'role': 'source', 'runtime': 'tools', 'interpreter': '/usr/local/bin/python',
                       'script': {'source': "import sys; sys.stdout.write('abc')"}, 'inputs': {},
                       'outputs': {'result': {'view': 'bytes', 'binding': 'stdout', 'format': 'text'}}},
            'upper': {'type': 'sdk', 'role': 'transform', 'runtime': 'jvm',
                      'program': {'path': str(jar), 'entrypoint': args.jvm_entrypoint},
                      'inputs': {'source': {'representations': ['bytes'], 'formats': []}}}},
            'steps': [
                {'id': 'source', 'operator': {'inline': 'source'}, 'inputs': {}, 'params': {}},
                {'id': 'upper', 'operator': {'inline': 'upper'}, 'inputs': {'source': 'source'}, 'params': {}},
                {'id': 'client_sink', 'operator': 'client_sink@1.0', 'inputs': {'result': 'upper'}}]}
        (base/'jvm-job.yaml').write_text(json.dumps(jvm_job))
        result = subprocess.run([str(args.runner.resolve()), str(base/'engine.yaml'), str(base/'jvm-job.yaml')],
                                cwd='/', capture_output=True, timeout=120)
        assert result.returncode == 0 and result.stdout == b'ABC', result.stderr.decode()[-8000:]
        print('PASS: inline Scala SDK JAR executes in the configured JVM runtime')
