#!/usr/bin/env bash
# Known-vulnerability check of what ships: backend runtime libraries (via osv.dev) and frontend production dependencies (npm audit).
# Needs network. Not part of verify.sh because the answer changes over time; run it before a release and on a schedule.
set -uo pipefail
cd "$(dirname "$0")/.."
status=0
echo "== backend runtime libraries (osv.dev)"
(cd backend && ./gradlew -q --console=plain printRuntimeDeps) | python3 -I scripts/osv_check.py || status=1
echo
echo "== frontend production dependencies (npm audit)"
(cd frontend && npm audit --omit=dev --audit-level=high) || status=1
exit $status
