#!/usr/bin/env bash
# The project's quality gate, host-neutral: run it locally or from any CI system.
#   scripts/verify.sh          backend + frontend gates (needs Docker for the backend's Testcontainers)
#   scripts/verify.sh --e2e    also runs the browser end-to-end test (needs the stack running: see docs/engineering/development-setup.md)
set -euo pipefail
cd "$(dirname "$0")/.."

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

step "Backend: compile, unit + integration tests (real PostgreSQL), module boundaries, API contract"
(cd backend && ./gradlew --console=plain clean build)

step "Frontend: lint (no warnings), strict type check, unit/UI tests, production build"
(cd frontend && npm ci --no-audit --no-fund && npm run lint && npm run typecheck && npm test && npm run build)

step "API contract: the committed client types must match what the committed OpenAPI snapshot generates"
schema=frontend/src/lib/api/schema.d.ts
before=$(sha256sum "$schema" | cut -d' ' -f1)
(cd frontend && npm run gen:api >/dev/null)
after=$(sha256sum "$schema" | cut -d' ' -f1)
if [[ "$before" != "$after" ]]; then
  echo "$schema was out of date with backend/openapi.json and has been regenerated; review and commit it" >&2
  exit 1
fi

if [[ "${1:-}" == "--e2e" ]]; then
  step "End-to-end (real browser, real backend)"
  (cd frontend && npx playwright test)
fi

printf '\n\033[32mAll gates passed.\033[0m\n'
