# DevForge AI

*From Issue to Solution.* A vendor-neutral AI software-engineering platform for Java and React projects. Give it a ticket or a description plus the repositories of a project; it investigates the real code, produces evidence-backed findings, and (in later milestones) proposes, validates and prepares fixes. A human approves every consequential step.

See [`CLAUDE.md`](CLAUDE.md) for the product model and engineering rules, and [`docs/`](docs/README.md) for the design, ADRs and the implementation plan.

## Status

| Milestone | State |
|---|---|
| M1 Functional prototype | Built: full run lifecycle on real repos with a **labelled simulation** of the AI step |
| M2 Real investigation | Built: Claude (each user's own key), read-only repo tools, evidence verification. Verified against a scripted provider, **not yet the live API** |
| M3 Jira via MCP | Built: tickets read with each user's own connection. Verified against a fake MCP server, **not yet Atlassian** |
| M4 Fix proposal, approved implementation, sandboxed validation | In progress: real proposal/edit/validate pipeline built and tested against a scripted model and a stub test runner; **not yet** live API or real Gradle/Maven builds |
| M2b OpenAI / Gemini, M5 branch/PR, M6 hardening | Not started |

Details and known gaps: [`docs/engineering/implementation-plan.md`](docs/engineering/implementation-plan.md).

## How it fits together

- **Backend** (`backend/`): Java 21, Spring Boot 4, PostgreSQL + Flyway, a modular monolith (Spring Modulith + ArchUnit). The API contract is `backend/openapi.json`.
- **Frontend** (`frontend/`): React 19, TypeScript, Vite, Tailwind, TanStack Query; the API client types are generated from the contract.
- **Providers are plug-ins** behind ports: AI model (`LlmClient`), ticket source (`Trackers`), Git host. Credentials are per user, encrypted and write-only.
- **Safety:** ticket text, repo files and tool output are untrusted data; the model cannot run anything itself; real agents are investigate-only for now.

## Run it locally (Linux)

Prerequisites: JDK 21, Node 22+, Docker.

```bash
scripts/dev.sh          # database + backend (127.0.0.1:8787) + UI (http://localhost:5173)
scripts/dev.sh logs     # follow the backend log
scripts/dev.sh stop
```

`scripts/dev.sh` creates a git-ignored `.env.local` holding the master key that encrypts stored connections; set `DEVFORGE_DEV_ADMINS` there to your email to get admin rights. Sign in with any email (local development only).

Manual start:

```bash
docker compose up -d postgres
cd backend
export DEVFORGE_MASTER_KEY="$(openssl rand -base64 32)" DEVFORGE_DEV_ADMINS="you@example.com"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd ../frontend && npm ci && npm run dev
```

## Trying the real pieces

- **Simulated (default):** add any 8+ character value as a Claude key under *My connections*, create a project with one or more local repository paths, start a run.
- **Real Claude:** set `DEVFORGE_AGENT_MODE=real` in `.env.local`, restart, add your own Anthropic API key, press *Verify*, start a run. *Investigate only* is read-only; the fix modes make edits in a private copy and run tests in a bubblewrap sandbox (needs `bwrap`; list your JDK/Node install directories in `devforge.execution.toolchain-mounts`).
- **Jira tickets:** add a *Jira (via MCP)* connection (`email:api-token` or a bearer token) and press *Verify*; then set the project's tracker under *People & policy* and start a run from a ticket key. Paste-the-text mode always works without it.

## Check everything

```bash
scripts/verify.sh           # backend + frontend gates
scripts/verify.sh --e2e     # plus the browser end-to-end test (stack must be running)
```

After changing the API: refresh the snapshot (`./mvnw test -Dopenapi.update=true`), review the diff, then `npm run gen:api`.

## Repository layout

```
backend/   Spring Boot app (package-by-module, ai.devforge.<module>)
frontend/  Vite app (feature folders)
docs/      product, architecture (ADRs), engineering plan, risks
scripts/   dev.sh (run locally), verify.sh (quality gate)
spikes/    throwaway experiments from M0 (not product code)
.claude/   project skills
```
