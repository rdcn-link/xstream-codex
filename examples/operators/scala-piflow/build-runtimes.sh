#!/usr/bin/env bash
# Usage: bash examples/operators/scala-piflow/build-runtimes.sh /path/to/cs
# Run as a Docker-authorized account. cs is the administrator's Linux Coursier binary.
set -euo pipefail
example_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "$example_dir/../../.." && pwd)
cs_binary=$(realpath "${1:?Provide the administrator-selected Coursier binary}")
(cd "$example_dir" && sbt -batch update)
cd "$project_root"
bash scripts/build-jvm-runtime.sh xstream/operator-jvm:2.0
bash scripts/build-scala-source-runtime.sh xstream/operator-jvm:2.0 xstream/operator-scala-build:2.0
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
python3 - "$build_dir" <<'PY'
from pathlib import Path
import os, platform, zipfile
output=Path(__import__('sys').argv[1])
cache=Path(os.environ.get('COURSIER_CACHE',str(Path.home()/'.cache/coursier')))
jars=list(cache.rglob('sqlite-jdbc-3.49.1.0.jar'))
if not jars:raise SystemExit('SQLite JDBC 3.49.1.0 was not resolved')
arch={'x86_64':'x86_64','aarch64':'aarch64'}[platform.machine()]
with zipfile.ZipFile(jars[0]) as jar:
 (output/'libsqlitejdbc.so').write_bytes(jar.read('org/sqlite/native/Linux/'+arch+'/libsqlitejdbc.so'))
PY
cp "$cs_binary" "$build_dir/cs"
python3 - "$cs_binary" "$build_dir/cs-libs" <<'PYLIB'
import re, shutil, subprocess, sys
from pathlib import Path
root=Path(sys.argv[2])
for name in re.findall(r'(?<!\S)(/[^\s]+)',subprocess.check_output(['ldd',sys.argv[1]],text=True)):
 source=Path(name);target=root/source.relative_to('/')
 target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
PYLIB
chmod 755 "$build_dir/cs"
cat > "$build_dir/Dockerfile" <<'DOCKER'
FROM xstream/operator-jvm:2.0
ENV XSTREAM_RUNTIME_REVISION=2.1
COPY libsqlitejdbc.so /opt/native/
ENV JAVA_TOOL_OPTIONS="-Dorg.xerial.snappy.lib.path=/opt/native -DZstdNativePath=/opt/native/libzstd-jni.so -Dorg.sqlite.lib.path=/opt/native"
DOCKER
cat > "$build_dir/Dockerfile.build" <<'DOCKER'
FROM xstream/operator-scala-build:2.0
COPY cs /usr/local/bin/cs
COPY cs-libs/ /
COPY libsqlitejdbc.so /opt/native/
ENV COURSIER_REPOSITORIES=https://repo.maven.apache.org/maven2 JAVA_TOOL_OPTIONS="-Dorg.xerial.snappy.lib.path=/opt/native -DZstdNativePath=/opt/native/libzstd-jni.so -Dorg.sqlite.lib.path=/opt/native"
DOCKER
docker build --network none -t xstream/operator-jvm-sqlite:2.1 "$build_dir"
docker build --network none -t xstream/operator-scala-dependencies:2.1 -f "$build_dir/Dockerfile.build" "$build_dir"
# Register immutable image IDs and exported archives as jvm-arrow@2.1 through the repository admin UI.
