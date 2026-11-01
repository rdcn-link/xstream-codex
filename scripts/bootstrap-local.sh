#!/usr/bin/env bash
# Publish this checkout's development artifacts in dependency order.
set -euo pipefail
if [[ $# != 0 ]]; then echo 'Usage: scripts/bootstrap-local.sh' >&2; exit 2; fi
xstream_root="$(cd "$(dirname "$0")/.." && pwd)"
for module in xstream-data xstream-execution-api connectors/xstream-dacp-protocol connectors/xstream-dacp-client xstream-operator-sdk xstream-operator-inference plugins/xstream-plugin-operator-executors plugins/xstream-plugin-execution-file plugins/xstream-plugin-source-analysis xstream-engine; do
  (cd "$xstream_root/$module" && sbt -batch publishLocal)
done
echo 'XStream development artifacts are ready.'
