# Development Setup (Linux)

Nothing below is implemented yet; this is the target. Items marked ✔ were observed on the dev machine on 2026-10-22.

## Prerequisites
| Tool | Version | Status |
|---|---|---|
| JDK | 21 | ✔ 21.0.2 |
| Node.js / npm | ≥ 22 / ≥ 10 | ✔ 22.23.2 / 10.9.8 |
| Docker (rootless preferred) | ≥ 27 | ✔ Docker Desktop 29.x working (needed for the database and Testcontainers). Earlier M0 note about group access is obsolete; sandbox limits still unverified. bubblewrap (`bwrap`) available as a Tier-1 stand-in |
| git | ≥ 2.40 | ✔ 2.53 |
| Maven | via wrapper (`./mvnw`) | not installed — not needed |
| ripgrep | any | to verify |
| PostgreSQL | 16 via Docker | no local psql |
| AI provider key (Claude / OpenAI / Gemini) | per user, entered in the UI | each developer supplies their own |
| Jira MCP | Atlassian remote MCP (`/v2/mcp`) | ✔ read via interactive OAuth in a Claude Code session; backend/headless access not verified |

## Environment (`.env.local`, git-ignored; `.env.example` is committed with placeholders)
```
DEVFORGE_DB_URL=jdbc:postgresql://localhost:5432/devforge
DEVFORGE_DB_USER=devforge
DEVFORGE_DB_PASSWORD=<local-only>
DEVFORGE_REPO_ROOT=/var/lib/devforge/repos        # managed clones/worktrees
DEVFORGE_MASTER_KEY=<base64 32 bytes, local-only>   # encrypts the per-user credential vault
JIRA_MCP_URL=<server url>   JIRA_MCP_TOKEN=<secret>   # project-level tracker integration
GIT_HOST_TOKEN=<secret>                          # only if a Git-host adapter is configured
# AI provider keys are NOT env vars: each user adds their own in the UI (My AI providers)
```
Never commit real values.

## Quick start (backend on :9090, UI on :8080)
```bash
scripts/dev.sh            # starts Postgres (docker), the backend on 127.0.0.1:9090 and the UI on http://localhost:8080
scripts/dev.sh logs       # follow the backend log
scripts/dev.sh stop       # stops the backend and the UI it started (never other programs on those ports)
```
Open http://localhost:8080 and sign in with any email (local development only). Put your email in `DEVFORGE_DEV_ADMINS` in `.env.local` (created on first run) to get admin rights. If 9090 or 8080 is taken by another program, `scripts/dev.sh` refuses to start and says so; change the backend port with `DEVFORGE_PORT` and the UI port in `frontend/vite.config.ts` (`DEVFORGE_API` points the UI proxy at a different backend).

## Commands (working as of M1)
```bash
docker compose up -d postgres                                      # database on :5432
cd backend && ./mvnw verify                                        # build + all tests (needs Docker: Testcontainers)
export DEVFORGE_MASTER_KEY="$(openssl rand -base64 32)" DEVFORGE_DEV_ADMINS="you@example.com"
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # API on :9090
cd frontend && npm ci && npm run dev                               # UI on :8080, proxies /api to :9090
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
cd frontend && npm run gen:api                                     # regenerate client types from backend/openapi.json
cd frontend && npx playwright install chromium && npm run e2e      # real-browser test (stack must be running)
scripts/verify.sh [--e2e]                                          # everything above, as one gate
```
To accept an intentional API change: `cd backend && ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true`, review the `openapi.json` diff, then `npm run gen:api`.
Real model mode: set `DEVFORGE_AGENT_MODE=real` (default `simulated`); users then add their own Anthropic API key in the UI. Live evaluation against the real API (costs money, runs only if the key is set): `ANTHROPIC_API_KEY=sk-... ./mvnw test -Dtest=LiveEvalTest` → `backend/target/eval/live-report.json`.
Environment: `DEVFORGE_DB_URL/USER/PASSWORD`, `DEVFORGE_MASTER_KEY` (required; losing it makes stored connections unreadable), `DEVFORGE_DEV_ADMINS`, `DEVFORGE_PORT` (default 9090). The AI keys are **not** environment variables: each user adds their own in the UI. Repositories may only be registered under `devforge.repositories.allowed-roots` (default: your home directory).
The "local" profile enables **header-based dev sign-in**; never expose a backend running it.

## Local safety
Register repos as managed clones/worktrees, not your daily checkout. Sandbox containers have no network; the first dependency warm-up for each repo needs network in the "deps" step. Local host memory is tight—keep concurrency at 1-2.
