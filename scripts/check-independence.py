#!/usr/bin/env python3
"""Validate that XStream builds only from this checkout and XStream-owned artifacts."""
from pathlib import Path
import re
import sys
from zipfile import ZipFile

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
# Engine/public contracts must not import a service SDK or a transport implementation.
for module in ('xstream-engine','xstream-execution-api'):
    for path in (root/module/'src/main').rglob('*.scala'):
        if re.search(r'xstream\.connector\.dacp|org\.apache\.arrow\.flight|dacp\.union',path.read_text()):
            errors.append(f'{path.relative_to(root)}: core depends on a DACP connector or Flight transport')
engine_build=(root/'xstream-engine/build.sbt').read_text()
if 'xstream-dacp-client' in engine_build or 'xstream-dacp-protocol' in engine_build:
    errors.append('engine build depends directly on a DACP connector')
for module in ('xstream-engine','xstream-execution-api','xstream-data'):
    for path in (root/module).rglob('*.scala'):
        if 'target' not in path.parts and re.search(r'^package dacp(?:\.|\s|$)',path.read_text(),re.M):
            errors.append(f'{path.relative_to(root)}: XStream-owned implementation has a DACP package')
for path in (root/'xstream-designer/src/main').rglob('*.scala'):
    if re.search(r'\b(?:PhysicalPlanner|GraphPlacement|PlacementOptimizer)\b',path.read_text()):
        errors.append(f'{path.relative_to(root)}: Designer must not plan execution')
bootstrap = (root / 'scripts/bootstrap-local.sh').read_text()
if 'dacp-codex' in bootstrap or 'dacp_root' in bootstrap:
    errors.append('bootstrap requires an external project')
# Also inspect built application libraries, when staged distributions are available.
for module in ('xstream-engine', 'xstream-designer'):
    for path in (root / module / 'target/universal/stage/lib').glob('*.jar'):
        if re.search(r'(?:^|\.)dacp-(?:plugin|union|client|server)', path.name):
            errors.append(f'{module}: DACP artifact in distribution: {path.name}')
# Reject stale compiled aliases as well as source-level coupling.
for module in ('xstream-engine','xstream-execution-api'):
    jar=root/module/'target/scala-2.13'/f'{module}_2.13-0.1.0-SNAPSHOT.jar'
    if jar.is_file():
        with ZipFile(jar) as archive:
            if any(name.startswith('dacp/') and name.endswith('.class') for name in archive.namelist()):
                errors.append(f'{module}: stale DACP namespace classes in artifact; clean and rebuild')
for error in errors:
    print(error, file=sys.stderr)
print(f'XStream independence: {len(errors)} errors')
raise SystemExit(bool(errors))
