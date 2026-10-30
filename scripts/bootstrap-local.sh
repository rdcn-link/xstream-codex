#!/usr/bin/env bash
# Bootstrap unreleased cross-project integration artifacts in dependency order.
set -euo pipefail
xstream_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ $# != 1 ]]; then echo 'Usage: scripts/bootstrap-local.sh /path/to/dacp-codex' >&2; exit 2; fi
dacp_root="$(cd "$1" && pwd)"
(cd "$xstream_root/xstream-data" && sbt -batch publishLocal)
for module in dacp-plugin-api dacp-union-protocol dacp-client-scala plugins/dacp-plugin-execution-file; do
  (cd "$dacp_root/$module" && sbt -batch publishLocal)
done
for module in xstream-operator-sdk xstream-operator-inference plugins/xstream-plugin-operator-executors xstream-engine; do
  (cd "$xstream_root/$module" && sbt -batch publishLocal)
done
echo 'Development artifacts ready for both repositories.'
