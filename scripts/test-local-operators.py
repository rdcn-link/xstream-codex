#!/usr/bin/env python3
"""Real Docker pipeline: source builds, immutable cache reuse and source invalidation."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import yaml

root = Path(__file__).resolve().parents[1]
runner = root / 'xstream-engine/target/universal/stage/bin/xstream-runner'
example = root / 'examples/operators/local'
with tempfile.TemporaryDirectory(prefix='xstream-local-smoke-') as temporary:
    directory = Path(temporary)
    shutil.copytree(example, directory / 'operators', ignore=shutil.ignore_patterns('target', '__pycache__'))
    config = yaml.safe_load((example / 'engine.yaml').read_text())
    config['datasets']['weather']['source']['root'] = str(directory / 'operators/data')
    config['operator_cache'] = str(directory / 'cache')
    config['execution_directory'] = str(directory / 'history')
    path = directory / 'engine.yaml'
    path.write_text(yaml.safe_dump(config))
    job = directory / 'operators/job.yaml'
    expected = (example / 'data/local-input.txt').read_bytes().upper()
    def run():
        result = subprocess.run([str(runner), str(path), str(job)], cwd=directory,
                                capture_output=True, timeout=180)
        assert result.returncode == 0, result.stderr.decode(errors='replace')[-10000:]
        assert result.stdout == expected, result.stdout
    run()
    cache = directory / 'cache/local-builds'
    first = {p: p.stat().st_mtime_ns for p in cache.glob('*/artifact.json')}
    assert len(first) == 2
    run()
    assert first == {p: p.stat().st_mtime_ns for p in cache.glob('*/artifact.json')}
    source = directory / 'operators/python/src/upper_ascii.py'
    source.write_text(source.read_text() + '\n# Source edit triggers a fresh artifact.\n')
    run()
    assert len(list(cache.glob('*/artifact.json'))) == 3
print('PASS: local Item -> Python -> Scala, identical streams, cache reuse and source invalidation')
