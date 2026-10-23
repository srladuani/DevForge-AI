#!/usr/bin/env bash
# Local development launcher: database + backend (:9090, loopback only) + UI (:8080).
#   scripts/dev.sh          start everything in the background
#   scripts/dev.sh stop     stop the backend and the UI (the database keeps running)
#   scripts/dev.sh logs     follow the backend log
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .dev

# pid listening on a port, but only if it is one of ours (its working directory is inside this repo): other local servers are never touched.
own_pid() {
  local pid; pid=$(ss -ltnp 2>/dev/null | grep ":$1 " | sed 's/.*pid=\([0-9]*\).*/\1/' | head -1 || true)
  [[ -n "$pid" ]] || return 0
  local cwd; cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null || true)
  if [[ "$cwd" == "$PWD"* ]]; then echo "$pid"; else echo "foreign:$pid"; fi
}
stop_port() { local p; p=$(own_pid "$1"); case "$p" in "") ;; foreign:*) echo "port $1 is used by another program (pid ${p#foreign:}); left alone" ;; *) kill "$p" && echo "stopped :$1" ;; esac; true; }
need_free_or_ours() { local p; p=$(own_pid "$1"); if [[ "$p" == foreign:* ]]; then echo "Port $1 is in use by another program (pid ${p#foreign:}). Stop it, or change the port (backend: DEVFORGE_PORT, UI: frontend/vite.config.ts)." >&2; exit 1; fi; }

case "${1:-start}" in
  stop) stop_port 9090; stop_port 8080; exit 0 ;;
  logs) exec tail -f .dev/backend.log ;;
esac

# Local settings live in .env.local (git-ignored). The master key is generated once and kept, so saved connections survive restarts.
if [[ ! -f .env.local ]]; then
  {
    echo "DEVFORGE_MASTER_KEY=$(openssl rand -base64 32)"
    echo "DEVFORGE_DEV_ADMINS=${DEVFORGE_DEV_ADMINS:-admin@example.com}"
  } > .env.local
  chmod 600 .env.local
  echo "created .env.local (edit DEVFORGE_DEV_ADMINS to make your email an admin)"
fi
set -a; source .env.local; set +a

need_free_or_ours 9090; need_free_or_ours 8080
docker compose up -d postgres >/dev/null
until docker compose exec -T postgres pg_isready -U devforge >/dev/null 2>&1; do sleep 1; done

if ! ss -ltn | grep -q ":9090 "; then
  (cd backend && nohup ./gradlew -q bootRun > ../.dev/backend.log 2>&1 &)
fi
until curl -fs localhost:9090/actuator/health >/dev/null 2>&1; do sleep 2; done

if ! ss -ltn | grep -q ":8080 "; then
  (cd frontend && [[ -d node_modules ]] || npm ci --no-audit --no-fund; nohup npm run dev > ../.dev/ui.log 2>&1 &)
  until curl -fs localhost:8080 >/dev/null 2>&1; do sleep 1; done
fi

echo
echo "DevForge is running:  http://localhost:8080   (API on 127.0.0.1:9090)"
echo "Stop with: scripts/dev.sh stop     Logs: scripts/dev.sh logs"
