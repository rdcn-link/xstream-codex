#!/usr/bin/env python3
"""Verify local source publication excludes host settings and generated files."""
import importlib.util
from pathlib import Path
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('submit_operator', root / 'xstream-operator-sdk/cli/xstream_operator_cli/cli.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary) / 'operator'
    (directory / 'src').mkdir(parents=True)
    (directory / 'resources').mkdir()
    (directory / 'target').mkdir()
    (directory / 'src/main.py').write_text('pass\n')
    (directory / 'resources/table.csv').write_text('value\n1\n')
    (directory / 'local.yaml').write_text('private-machine-settings')
    (directory / 'target/operator.jar').write_bytes(b'build-output')
    archive = module.package_directory(directory, Path(temporary) / 'source.zip')
    with zipfile.ZipFile(archive) as source:
        assert set(source.namelist()) == {'src/main.py', 'resources/table.csv'}
    (directory / 'src/link.py').symlink_to(directory / 'local.yaml')
    try:
        module.package_directory(directory, archive)
        raise AssertionError('source symlink accepted')
    except ValueError:
        pass
print('PASS: local publication selects sources/resources only and rejects symlinks')
