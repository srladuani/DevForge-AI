# DevForge AI — Operations Runbook

For whoever runs a DevForge deployment (one organisation per deployment). Everything here is checked against the code; where something is untested against a real environment, it says so.

## What runs
One Spring Boot process (API + workflow workers + schedulers), PostgreSQL, and optional outbound calls: an AI provider (per user's key), Jira MCP and GitHub (per user's token). Sandbox runs are local processes (bubblewrap). There is no other state: **backup = the PostgreSQL database + the master key**.

## Start, stop, health
- Local: `scripts/dev.sh [stop|logs]`. A server: `./gradlew bootJar` and `java -jar backend/build/libs/*.jar` with the environment below.
- Health: `GET /actuator/health` (public; `/liveness`, `/readiness`). Everything else under `/actuator` needs an admin.
- Required environment: `DEVFORGE_DB_URL/USER/PASSWORD`, `DEVFORGE_MASTER_KEY` (32 bytes, base64), `DEVFORGE_AGENT_MODE` (`simulated` default, `real`). The `local` profile enables **header-based dev sign-in and must never run on a server** (it refuses to bind beyond loopback).

## Sign-in (OIDC)
Production sign-in is OpenID Connect; the local development sign-in (`local` profile) must not be used on a server.
1. At your identity provider create a **public** client (no secret) for the browser app using *authorization code + PKCE*, with redirect URI `https://<your-host>/auth/callback` and post-logout URI `https://<your-host>/`. Allowed scopes: `openid profile email` (add `offline_access` if the provider can issue refresh tokens: then tokens renew silently; otherwise people sign in again when the token expires).
2. The API validates the **access token** the browser sends: it must be signed by the issuer's keys, unexpired, carry the audience you configure, and contain an `email` claim (configure the provider to put `email` and `name` in access tokens; `email_verified=false` is refused).
3. Set `DEVFORGE_OIDC_ISSUER_URI`, `DEVFORGE_OIDC_CLIENT_ID`, `DEVFORGE_OIDC_AUDIENCE` (use it), and optionally `DEVFORGE_OIDC_ADMIN_VALUE` (+ `DEVFORGE_OIDC_ADMIN_CLAIM`, default `groups`): people whose token carries that value are administrators, and lose the right when the provider stops sending it.
4. Serve the UI and `/api` from the **same origin** (a reverse proxy); the API sends no CORS headers.
People are created on first sign-in and then added to projects by a project admin (by email). If the provider is unreachable the application still starts; sign-ins fail until it is back. **Not verified against a real provider** (token validation and the browser flow are tested with a locally signed token and a fake `UserManager`).

## Single sign-on for Jira (OAuth)
Some Atlassian organisations do not allow API tokens on the MCP server (an API-token call then fails with "You don't have permission to connect via API token"). People can instead press **Sign in with Jira (via MCP)** under *My connections*. DevForge discovers Atlassian's login from the MCP server, registers itself automatically (dynamic client registration), uses authorization code + PKCE with read-only scopes plus offline access, and stores the tokens encrypted in that person's own connection, renewing them automatically (a revoked sign-in shows "sign in again").
- The browser returns to `<DEVFORGE_OAUTH_REDIRECT_BASE>/api/v1/oauth/callback` (default `http://localhost:8080`). Behind a reverse proxy set it to the public address; Atlassian must accept that redirect address.
- Addresses learned from Atlassian's metadata must be https and in the same organisation as the MCP server, or the sign-in is refused before anything is sent.
- **Not verified against Atlassian:** the flow is tested against a fake login service; whether Atlassian accepts a localhost redirect address for a self-registered client is unconfirmed. If it refuses, ask the Atlassian organisation admin to allow the redirect address, or to enable API tokens (Atlassian Administration, Rovo MCP server settings).

## Publishing to a Git host (GitHub, Bitbucket Cloud)
The run's owner opens the **Changes** tab and presses **Create pull request…**. The plan appears in full (host, repository, branch, target branch, files); a person with approval rights presses **Confirm: push and create the pull request**, which approves exactly that plan. Then DevForge makes one commit on the real base commit, pushes a new branch (never forced) and opens the pull request with the owner's own credential. Nothing is merged.
- **Per project** (People & policy → *Pull requests*, admin): the **target branch** (for example `develop`; empty = each repository's default branch on the host), the **branch prefix** (`devforge/` by default; for example `feature/`) and whether pull requests open as **drafts**.
- **The host is found from each checkout's `origin` remote** (https or ssh form), so one project may mix GitHub and Bitbucket repositories; the owner needs a verified connection for each host used. A remote on any other host is refused and no credential is sent there.
- **Bitbucket Cloud credentials:** sign in with single sign-on (below), or add a *repository/workspace access token* or an *app password* (`username:app-password`) under My connections. Verify uses `GET /user`; a repository access token has no account scope and is reported as accepted-but-unconfirmed (it is exercised on the first publication). **Not verified against bitbucket.org**; whether the API accepts the `draft` field on pull request creation is unconfirmed.
- Repositories whose base commit has local commits that were never pushed will push those commits too.

## Single sign-on for Git hosts (OAuth app registered by an administrator)
Bitbucket Cloud and GitHub have no automatic client registration, so an administrator registers an OAuth app once and gives DevForge its client id and secret (server environment). People then press **Sign in with Bitbucket Cloud / GitHub** under My connections; their own tokens are stored encrypted in their own connection and renewed automatically.
- **Callback address to register:** `<DEVFORGE_OAUTH_REDIRECT_BASE>/api/v1/oauth/callback` (default `http://localhost:8080/api/v1/oauth/callback`).
- **Bitbucket Cloud:** Workspace settings → *OAuth consumers* → *Add consumer*; set the callback URL; make it a private consumer; permissions *Account: Read*, *Repositories: Read and Write*, *Pull requests: Read and Write*. Set `DEVFORGE_OAUTH_BITBUCKET_CLIENT_ID` and `DEVFORGE_OAUTH_BITBUCKET_CLIENT_SECRET` (the consumer's Key and Secret).
- **GitHub:** Settings → Developer settings → *OAuth Apps* → *New OAuth App*; set the callback URL. Set `DEVFORGE_OAUTH_GITHUB_CLIENT_ID` and `DEVFORGE_OAUTH_GITHUB_CLIENT_SECRET`. (Requests scope `repo` and uses PKCE; GitHub Enterprise: override `devforge.oauth.providers.github.authorize-url` and `token-url`.)
- Until the client id and secret are set, the button is replaced by a note that single sign-on is not set up, and personal/access tokens keep working. **Tested against a fake login service; not verified against Bitbucket or GitHub** (the menu paths above are from memory of their current screens).

## Configuration that matters (all `devforge.*`, env-overridable)
| Setting | Default | Meaning |
|---|---|---|
| `workflow.concurrency` | 2 | runs executing at once per process |
| `workflow.max-runs-per-user-per-day` | 100 | rolling 24 h; `0` = unlimited. Over it: HTTP 429 `QUOTA_EXCEEDED` |
| `workflow.max-tokens-per-user-per-day` | 5,000,000 | real-provider tokens, rolling 24 h; `0` = unlimited |
| `workflow.retention-days` | 0 (keep) | finished runs older than this are deleted with all their data (audit log is kept) |
| `execution.work-root` | `<tmp>/devforge-work` | private working copies and dependency caches |
| `execution.work-ttl` / `cache-ttl` | 6 h / 14 d | sweeper removes older leftovers |
| `execution.toolchain-mounts` | none | JDK/Node/Gradle installs the sandbox may see (read-only) |
| `agent.max-turns/max-tool-calls/max-run-tokens` | 24 / 40 / 300,000 | per-step limits |

Per-project limits (concurrent runs, allowed providers, different-approver rule) are edited in the UI by a project admin.

## Background housekeeping (no action needed; know it exists)
- **Work directory sweeper** (hourly): deletes working copies older than `work-ttl` and unused caches older than `cache-ttl`. Only touches `work-root`.
- **Publication recovery** (every minute): resumes a publication that stopped mid-way (for example by a restart) after 3 idle minutes. Safe to repeat: commits are deterministic, branches are never forced, an existing PR is reused.
- **Approval expiry**: pending approvals expire after their TTL (checked on read).
- **Run retention** (every 6 h, only if `retention-days` > 0).
- **Job leases**: a job whose worker stopped heartbeating is re-queued automatically after `leaseSeconds`.

## Monitoring
Scrape `/actuator/prometheus` with an admin identity. Alert rules: `ops/prometheus/devforge-alerts.yml`; Grafana dashboard: `ops/grafana/devforge-dashboard.json`. Key series: `devforge_run_status{status}`, `devforge_jobs_queued`, `devforge_jobs_oldest_queued_seconds`, `devforge_step_duration_seconds_*{step,outcome}`, `devforge_model_tokens_total{provider,direction,simulated}`, `devforge_publications_total{outcome,code}`, `devforge_sandbox_available`, `devforge_sandbox_run_seconds_*`. Metrics contain no user, run, ticket or credential data. **Not verified against a real Prometheus/Grafana** (rules and dashboard are validated for syntax only).

## Procedures
**Runs are not progressing** (`DevForgeJobsStuck`): (1) `jobs_oldest_queued_seconds` high and `run_status{status=~"INVESTIGATION|…"}` flat → workers are not claiming: check the log for `JobWorker` errors, DB connectivity, `workflow.concurrency`. (2) Jobs stay `LEASED` with a dead worker: they re-queue after the lease; a restart speeds it up. (3) A single run stuck in a step: the owner or an admin can *Cancel*; a failed retryable run can be *Resumed* by its owner.

**A user's credential stopped working** (run is `AWAITING_INPUT`/`CREDENTIAL` or a publication `HOST_AUTH`): the owner replaces the connection under *My connections* and presses *Resume* / *Try again*. Credentials are never shared or fallen back to another user's.

**Sandbox** (`DevForgeSandboxUnavailable`): validation then reports `NOT_RUN` with the reason; nothing runs unisolated. Check `bwrap` is installed and unprivileged user namespaces are allowed (`sysctl kernel.unprivileged_userns_clone`, AppArmor restrictions on newer Ubuntu). Toolchain missing in the sandbox → add the install dir to `execution.toolchain-mounts`. Memory limits are **not** enforced by this backend (Docker backend not built): watch host memory when raising `workflow.concurrency`.

**Disk filling up**: look at `<work-root>` (and `/tmp`). Lower `work-ttl`/`cache-ttl` or run the sweeper's logic by deleting old directories; enable `retention-days` for database growth.

**Rotate the master key**: add the new key as `devforge.security.keys.<n>` and raise `current-key-version`; old versions stay configured so existing secrets still decrypt. Then an admin calls `POST /api/v1/admin/vault/rotate` to re-encrypt existing secrets under the current key version (not exercised against a large vault). Losing every key that encrypted a secret makes it unreadable: users must re-add that connection.

**Suspected leak of a user token**: the user revokes it at the provider, then deletes the connection in DevForge. DevForge never logs secrets (tests assert this for events, metrics and API responses), but check the audit log (`/api/v1/admin/audit`) for the connection's use.

**Back up / restore**: `pg_dump` the database; keep the master key(s) separately from the dump. Restore the dump, set the same keys, start. Unfinished runs resume from their checkpoints; unfinished publications are picked up by recovery.

## Upgrades
Flyway migrations run at start and are forward-only. Take a dump first. The API contract is `backend/openapi.json`; a breaking change shows up in the contract test and the committed client types.

## Known limits (honest list)
Not verified against the live Anthropic API, Atlassian, or github.com; Docker sandbox backend and memory limits not built; no OIDC login in the UI yet (see the security notes); one process (no worker fleet); metrics rules unverified in a real Prometheus.
