#!/usr/bin/env python3
"""Validate that XStream builds only from this checkout and XStream-owned artifacts."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
errors = []
for path in root.rglob('*.sbt'):
    if 'target' in path.parts:
        continue
    source = path.read_text()
    for name in re.findall(r'"io.github.rdcn-link"\s*%%?\s*"([^"]+)"', source):
        if not name.startswith('xstream-'):
            errors.append(f'{path.relative_to(root)}: external project artifact {name}')
    for reference in re.findall(r'RootProject\(file\("([^"]+)"\)\)', source):
        target = (path.parent / reference).resolve()
        if not target.is_relative_to(root) or not (target / 'build.sbt').is_file():
            errors.append(f'{path.relative_to(root)}: invalid project reference {reference}')
for path in (root / 'xstream-operator-inference/src').rglob('*.scala'):
    if 'dacp.union' in path.read_text() or 'dacp.client' in path.read_text():
        errors.append(f'{path.relative_to(root)}: inference depends on service/client implementation')
bootstrap = (root / 'scripts/bootstrap-local.sh').read_text()
if 'dacp-codex' in bootstrap or 'dacp_root' in bootstrap:
    errors.append('bootstrap requires an external project')
# Also inspect built application libraries, when staged distributions are available.
for module in ('xstream-engine', 'xstream-designer'):
    for path in (root / module / 'target/universal/stage/lib').glob('*.jar'):
        if re.search(r'(?:^|\.)dacp-(?:plugin|union|client|server)', path.name):
            errors.append(f'{module}: DACP artifact in distribution: {path.name}')
for error in errors:
    print(error, file=sys.stderr)
print(f'XStream independence: {len(errors)} errors')
raise SystemExit(bool(errors))
