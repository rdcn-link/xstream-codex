#!/usr/bin/env python3
"""Create the reviewable source archive shared by the four submission definitions."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parent
output = root / 'target/script-sources.zip'
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((root / 'src').glob('*.py')):
        archive.write(path, path.relative_to(root))
    archive.write(root / 'pyproject.toml', 'pyproject.toml')
print(output)
