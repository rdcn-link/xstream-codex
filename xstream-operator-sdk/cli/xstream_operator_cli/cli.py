#!/usr/bin/env python3
"""Submit operator sources for repository-side analysis, isolated build and administrator review."""
import argparse
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tomllib
import tempfile
import zipfile
from urllib.parse import urlsplit
import yaml


def submit(definition, source, origin, token):
    url = urlsplit(origin)
    if (url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password
            or url.path not in ('', '/') or url.query or url.fragment):
        raise ValueError('repository URL must be an HTTP(S) origin')
    if url.scheme != 'https' and url.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise ValueError('remote submission requires HTTPS')
    if not token or any(c.isspace() for c in token):
        raise ValueError('invalid submitter Token')
    connection_type = http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection

    def request(method, path, content=None, kind='application/json', size=0, expected=200):
        connection = connection_type(url.hostname, url.port, timeout=300)
        try:
            connection.putrequest(method, path)
            if method != 'GET':
                connection.putheader('Authorization', 'Bearer ' + token)
                connection.putheader('Content-Type', kind)
                connection.putheader('Content-Length', str(size))
            connection.endheaders()
            if content is not None:
                for chunk in iter(lambda: content.read(1024 * 1024), b''):
                    connection.send(chunk)
            response = connection.getresponse()
            data = response.read(1024 * 1024)
            if response.status != expected:
                raise RuntimeError(f'HTTP {response.status}: {data[:4096].decode(errors="replace").strip()}')
            return json.loads(data)
        finally:
            connection.close()

    if definition.stat().st_size > 65536:
        raise ValueError('definition exceeds 64 KiB')
    class UniqueLoader(yaml.SafeLoader):
        pass
    def mapping(loader, node):
        values = {}
        for key, value in node.value:
            key = loader.construct_object(key)
            if key in values:
                raise ValueError('duplicate YAML key')
            values[key] = loader.construct_object(value)
        return values
    UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    n = yaml.load(definition.read_text(), Loader=UniqueLoader)
    allowed = {'name', 'version', 'description', 'icon', 'inputs', 'parameters', 'language', 'entrypoint', 'runtime', 'source_public', 'provenance', 'dependencies', 'submission', 'binary', 'execution_config'}
    if not isinstance(n, dict) or not set(n).issubset(allowed):
        raise ValueError('unsupported definition fields; do not provide artifacts or classifications')
    if not isinstance(n.get('source_public'), bool):
        raise ValueError('source_public must explicitly be true or false')
    languages = {r['language']: r['executor'] for r in request('GET', '/v1/build-languages')}
    language = n['language']
    if language not in languages:
        raise ValueError('choose a build language supported by the repository plugins')
    reference = n['runtime']
    runtimes = request('GET', '/v1/runtimes')
    runtime = next((r for r in runtimes if r['name']+'@'+r['version'] == reference), None)
    if runtime is None or language not in runtime['build']['languages']:
        raise ValueError('choose a compatible runtime from /v1/runtimes')

    def upload(path):
        if not re.fullmatch(r'[A-Za-z0-9_-][A-Za-z0-9_.-]*', path.name):
            raise ValueError('invalid source-material filename')
        if not 0 < path.stat().st_size <= 64 * 1024 * 1024:
            raise ValueError('source materials must be nonempty and at most 64 MiB')
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        value = digest.hexdigest()
        with path.open('rb') as stream:
            request('PUT', '/v1/blobs/sha256/'+value, stream, 'application/octet-stream', path.stat().st_size, 201)
        return {'sha256': value, 'size': path.stat().st_size, 'filename': path.name}

    provenance = dict(n.get('provenance', {}))
    for key in ('license_file', 'sbom'):
        if key in provenance:
            provenance[key] = upload(definition.parent / provenance[key])
    if 'source_archive' in provenance:
        raise ValueError('provide source using --source, not provenance.source_archive')
    provenance['source_archive'] = upload(source)
    binary = None
    if n.get('submission', 'source') == 'binary':
        binary = dict(n['binary'])
        binary['artifact'] = upload(definition.parent / binary['artifact'])
    manifest = {key: n[key] for key in ('name', 'version', 'description', 'icon', 'inputs', 'parameters', 'source_public')}
    manifest.update(api_version=2, execution={'executor': languages[language], 'entrypoint': n['entrypoint'], 'config': n.get('execution_config', {})},
                    runtime={'image': runtime['image'], 'archive': runtime['archive']}, build={'language': language}, provenance=provenance)
    for key in ('dependencies', 'submission'):
        if key in n:
            manifest[key] = n[key]
    if binary is not None:
        manifest['binary'] = binary
    data = json.dumps(manifest, ensure_ascii=False).encode()
    result = request('POST', '/v1/submissions', io.BytesIO(data), size=len(data), expected=202)
    print(json.dumps(result))
    return result


def package_directory(directory, destination):
    """Archive publishable sources only: never local.yaml, build outputs, caches or credentials."""
    directory = directory.resolve()
    paths = []
    for name in ('src', 'resources', 'bin', 'lib'):
        root = directory / name
        if not root.exists():
            continue
        if root.is_symlink() or not root.is_dir():
            raise ValueError('source roots must be real directories')
        for path in sorted(root.rglob('*')):
            if path.is_symlink():
                raise ValueError('source archive cannot contain symlinks')
            if path.is_file() and '__pycache__' not in path.parts:
                paths.append(path)
    if not any(p.suffix in ('.py', '.java', '.scala') and p.relative_to(directory).parts[0] == 'src' for p in paths):
        raise ValueError('src/ must contain operator sources')
    for name in ('pyproject.toml', 'uv.lock', 'pom.xml', 'build.sbt', 'build.gradle', 'build.gradle.kts', 'dependencies.json'):
        path = directory / name
        if path.exists():
            if path.is_symlink() or not path.is_file():
                raise ValueError('dependency declarations must be regular files')
            paths.append(path)
    if len(paths) > 16384 or sum(p.stat().st_size for p in paths) > 64 * 1024 * 1024:
        raise ValueError('source archive exceeds 16384 files or 64 MiB')
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in paths:
            archive.write(path, path.relative_to(directory).as_posix())
    return destination


def check_directory(directory):
    directory = directory.resolve()
    n = yaml.safe_load((directory / 'operator.yaml').read_text())
    if not isinstance(n, dict) or not n.get('entrypoint') or not isinstance(n.get('language'), str):
        raise ValueError('operator requires a supported language and entrypoint')
    if not isinstance(n.get('source_public'), bool):
        raise ValueError('source_public must be explicit')
    deps = n.get('dependencies')
    if deps:
        if not isinstance(deps, dict) or not isinstance(deps.get('provider'), str) or not re.fullmatch(r'[a-z][a-z0-9_-]*', deps['provider']):
            raise ValueError('unknown dependency provider')
        if deps['provider'] == 'python':
            with (directory / 'pyproject.toml').open('rb') as f:
                project = tomllib.load(f)['project']
            if not isinstance(project.get('dependencies'), list) or not project.get('requires-python'):
                raise ValueError('pyproject.toml needs requires-python and dependencies')
    if n.get('submission', 'source') == 'binary':
        b = n['binary']
        if not {'artifact', 'source_url', 'version', 'platform', 'license'} <= b.keys():
            raise ValueError('binary provenance is incomplete')
        target = (directory / b['artifact']).resolve()
        if not target.is_relative_to(directory) or not target.is_file():
            raise ValueError('binary must be a file inside the operator directory')
    with tempfile.TemporaryDirectory() as temp:
        package_directory(directory, Path(temp) / 'check.zip')
    return n


def create_template(directory, language):
    directory = directory.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    (directory / 'src').mkdir()
    name = directory.name.replace('_', '-')
    python = language in ('python', 'shell')
    n = dict(name=name, version='1.0.0', description='Streaming identity operator',
             icon={'path': 'M3 12h18m-6-6 6 6-6 6', 'color': '#087d8c'},
             inputs={'source': {'representations': ['bytes'], 'formats': []}},
             parameters={'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': False},
             language='python' if python else 'java', entrypoint='main' if python else 'Identity',
             runtime='python-arrow@1.0' if python else 'jvm-arrow@1.0', source_public=True,
             dependencies={'provider': 'python' if python else 'jvm'})
    if python:
        (directory / 'pyproject.toml').write_text('[project]\nname = "'+name+'"\nversion = "1.0.0"\nrequires-python = ">=3.11,<3.14"\ndependencies = []\n')
        source = """from xstream_operator import BytesOperator, run_operator

class Identity(BytesOperator):
    def transform(self, block, params):
        return block

operator = Identity()
def process(ctx):
    operator.process(ctx)
def infer_outputs(inputs, params):
    return operator.infer_outputs(inputs, params)
def preview_outputs(inputs, params, limits):
    return operator.preview_outputs(inputs, params, limits)

if __name__ == '__main__':
    run_operator(process, infer_outputs, preview_outputs=preview_outputs)
"""
        if language == 'shell':
            source = source.replace('return block', "import subprocess\n        return subprocess.run(['/bin/sh', '-c', 'while IFS= read -r line; do printf \"%s\\n\" \"$line\"; done'], input=block, stdout=subprocess.PIPE, check=True).stdout")
            # A chunk template intentionally keeps the example stateless; full-stream tools use a custom process(ctx).
        (directory / 'src/main.py').write_text(source)
        build = {'provider': 'python'}
    else:
        (directory / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>local</groupId><artifactId>'+name+'</artifactId><version>1.0.0</version><dependencies/></project>\n')
        (directory / 'dependencies.json').write_text('[]\n')
        (directory / 'src/Identity.java').write_text("""import com.fasterxml.jackson.databind.JsonNode;
import xstream.operator.BytesOperator;
public final class Identity {
    public static void main(String[] args) { Identity$.MODULE$.main(args); }
}
""")
        (directory / 'src/Identity$.java').write_text("""import com.fasterxml.jackson.databind.JsonNode;
import xstream.operator.BytesOperator;
public final class Identity$ extends BytesOperator {
    public static final Identity$ MODULE$ = new Identity$();
    public byte[] transform(byte[] chunk, JsonNode params) { return chunk; }
}
""")
        (directory / 'target').mkdir()
        build = {'provider': 'jvm', 'image': 'xstream/operator-jvm:1.1'}
    (directory / 'operator.yaml').write_text(yaml.safe_dump(n, sort_keys=False))
    local = {'image': 'xstream/operator-python:1.0' if python else 'xstream/operator-jvm:1.1', 'executor': 'python' if python else 'jvm', 'build': build}
    (directory / 'local.yaml').write_text(yaml.safe_dump(local, sort_keys=False))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="xstream-operator", description="Develop and publish XStream operators.")
    parser.add_argument("--version", action="version", version="%(prog)s 0.1.0")
    commands = parser.add_subparsers(dest="command", required=True)
    init_parser = commands.add_parser('init', help='Create an operator project template')
    init_parser.add_argument('directory', type=Path)
    init_parser.add_argument('--language', choices=('python', 'java', 'shell'), default='python')
    check_parser = commands.add_parser('check', help='Check source layout and dependency declarations')
    check_parser.add_argument('directory', type=Path)
    build_parser = commands.add_parser('build', help='Build a local operator and locked dependency environment')
    build_parser.add_argument('directory', type=Path)
    build_parser.add_argument('--config', required=True, type=Path)
    build_parser.add_argument('--runner', default='xstream-runner')
    submit_parser = commands.add_parser("submit", help="Submit operator sources for build and review", description=__doc__)
    submit_parser.add_argument('definition', type=Path, help='Source submission YAML or local operator directory containing operator.yaml')
    submit_parser.add_argument('--source', type=Path, help='ZIP/TAR containing src/ and optional resources/')
    submit_parser.add_argument('--url', required=True)
    auth = submit_parser.add_mutually_exclusive_group(required=True)
    auth.add_argument('--token-file', type=Path)
    auth.add_argument('--token-env')
    args = parser.parse_args(argv)
    if args.command == 'init':
        create_template(args.directory, args.language)
        print(str(args.directory))
        return
    if args.command == 'check':
        check_directory(args.directory)
        print('Operator source and dependency declarations are valid')
        return
    if args.command == 'build':
        check_directory(args.directory)
        subprocess.run([args.runner, 'build-operator', str(args.config.resolve()), str(args.directory.resolve())], check=True)
        return
    token = args.token_file.read_text().strip() if args.token_file else os.environ.get(args.token_env, '')
    if args.definition.is_dir():
        if args.source is not None:
            submit_parser.error('--source cannot be used with a local operator directory')
        with tempfile.TemporaryDirectory(prefix='xstream-submit-') as temporary:
            source = package_directory(args.definition, Path(temporary) / 'operator-sources.zip')
            submit(args.definition / 'operator.yaml', source, args.url, token)
    else:
        if args.source is None:
            submit_parser.error('--source is required with a YAML definition')
        submit(args.definition, args.source, args.url, token)


if __name__ == '__main__':
    main()
