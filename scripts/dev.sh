#!/usr/bin/env bash
# Local development launcher: database + backend (:8787, loopback only) + UI (:5173).
#   scripts/dev.sh          start everything in the background
#   scripts/dev.sh stop     stop the backend and the UI (the database keeps running)
#   scripts/dev.sh logs     follow the backend log
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .dev

stop_port() { local pid; pid=$(ss -ltnp 2>/dev/null | grep ":$1 " | sed 's/.*pid=\([0-9]*\).*/\1/' | head -1 || true); [[ -n "$pid" ]] && kill "$pid" && echo "stopped :$1"; true; }

case "${1:-start}" in
  stop) stop_port 8787; stop_port 5173; exit 0 ;;
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

docker compose up -d postgres >/dev/null
until docker compose exec -T postgres pg_isready -U devforge >/dev/null 2>&1; do sleep 1; done

if ! ss -ltn | grep -q ":8787 "; then
  (cd backend && nohup ./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=local > ../.dev/backend.log 2>&1 &)
fi
until curl -fs localhost:8787/actuator/health >/dev/null 2>&1; do sleep 2; done

if ! ss -ltn | grep -q ":5173 "; then
  (cd frontend && [[ -d node_modules ]] || npm ci --no-audit --no-fund; nohup npm run dev > ../.dev/ui.log 2>&1 &)
  until curl -fs localhost:5173 >/dev/null 2>&1; do sleep 1; done
fi

echo
echo "DevForge is running:  http://localhost:5173   (API on 127.0.0.1:8787)"
echo "Stop with: scripts/dev.sh stop     Logs: scripts/dev.sh logs"
