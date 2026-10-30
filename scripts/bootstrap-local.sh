#!/usr/bin/env bash
# Bootstrap unreleased integration artifacts, then publish XStream for DACP consumers.
set -euo pipefail
xstream_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ $# != 1 ]]; then echo 'Usage: scripts/bootstrap-local.sh /path/to/dacp-codex' >&2; exit 2; fi
dacp_root="$(cd "$1" && pwd)"
(cd "$xstream_root/xstream-data" && sbt -batch publishLocal)
for module in dacp-plugin-api dacp-operator-sdk dacp-union-protocol dacp-client-scala dacp-operator-inference plugins/dacp-plugin-execution-file plugins/dacp-plugin-operator-executors; do
  (cd "$dacp_root/$module" && sbt -batch publishLocal)
done
(cd "$xstream_root/xstream-engine" && sbt -batch publishLocal)
echo 'Local artifacts ready. Both repositories now build independently using Maven/Ivy artifacts.'
