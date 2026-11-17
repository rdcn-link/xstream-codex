"""Compile operator sources without importing or executing publisher code."""
import ast
import json
import pathlib
import py_compile
import sys
import tempfile
import zipfile


def validate_preview_registration(tree, filename):
    api_keywords = {'run_operator': 'preview_outputs', 'run_inference': 'preview'}
    aliases = {}
    modules = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module and (
                node.module == 'xstream_operator' or node.module.startswith('xstream_operator.')):
            for name in node.names:
                if name.name == '*':
                    aliases.update(api_keywords)
                elif name.name in api_keywords:
                    aliases[name.asname or name.name] = api_keywords[name.name]
        elif isinstance(node, ast.Import):
            modules.update(n.asname or n.name for n in node.names if n.name == 'xstream_operator')
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        name = None
        if isinstance(node.func, ast.Name):
            name = node.func.id
        elif (isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name)
              and node.func.value.id in modules):
            name = node.func.attr
            keyword = api_keywords.get(name)
        else:
            continue
        if isinstance(node.func, ast.Name):
            keyword = aliases.get(name)
        if keyword and not any(k.arg == keyword and not (
                isinstance(k.value, ast.Constant) and k.value.value is None) for k in node.keywords):
            raise ValueError(
                f'{filename}:{node.lineno}: {name} requires explicit {keyword}= registration. '
                'Implement a bounded preview_outputs(inputs, params, limits); '
                'return {} when complete source files are required. '
                'Do not run production processing merely to generate a preview.')


def build(root, output):
    files = sorted(root.rglob('*'))
    if not root.is_dir() or not any(p.suffix == '.py' for p in files):
        raise ValueError('src/ must contain Python sources')
    modules = []
    with tempfile.TemporaryDirectory(prefix='xstream-compile-') as temporary:
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in files:
                if not path.is_file():
                    continue
                name = str(path.relative_to(root))
                if path.suffix == '.py':
                    tree = ast.parse(path.read_bytes(), filename=name)
                    validate_preview_registration(tree, name)
                    if any(isinstance(n, ast.FunctionDef) and n.name == 'process' for n in tree.body):
                        modules.append(name[:-3].replace('/', '.'))
                    compiled = str(pathlib.Path(temporary) / 'check.pyc')
                    py_compile.compile(str(path), cfile=compiled, dfile=name, doraise=True,
                                       invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH)
                    archive.write(compiled, name + 'c')
                elif name != '.dacp-build.json':
                    archive.write(path, name)
            archive.writestr('.dacp-build.json', json.dumps({'python_process_modules': modules}))


if __name__ == '__main__':
    destination = pathlib.Path('/output/operator.zip')
    build(pathlib.Path('/source/src'), destination)
    with destination.open('rb') as artifact:
        sys.stdout.buffer.write(artifact.read())
