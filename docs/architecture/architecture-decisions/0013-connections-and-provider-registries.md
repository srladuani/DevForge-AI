# ADR-0013: User connections and configurable provider registries
**Status:** Proposed (extends ADR-0008, ADR-0012)
## Context
- Users supply repository **links**; the platform must clone and work on any project. Git hosts differ (Bitbucket, GitHub, GitLab, self-hosted) and authenticate with **OAuth or an API key/token**.
- AI providers must be addable/updatable/removable from settings or in code, with Claude first.
- Sessions are user-based (ADR-0012); Git and tracker access should respect *that user's* permissions, not a shared super-account.

## Decision
### 1. One credential model: `Connection`
A user-owned connection has `kind` (`AI_PROVIDER | GIT_HOST | TRACKER`), `providerType` (e.g. `ANTHROPIC`, `GITHUB`, `JIRA`), `authMethod` (`API_KEY | PAT | OAUTH`), encrypted secret/refresh token (vault from ADR-0012), status, `lastVerifiedAt`. API is write-only; OAuth flows run server-side (authorization-code + PKCE; tokens stored encrypted, refreshed by the worker). Projects bind *which* host/tracker (URL, type) but the credential used is the **acting user's**. A project may optionally designate a shared service connection for the tracker (admin-set, still audited) where per-user auth is impractical.
- Ship order: **API key / PAT first** (no app registration), **OAuth second** (needs an admin-registered OAuth app per host: client id/secret in server config; self-hosted hosts need their own registration). Verify each host's currently supported token types in spike S5 (vendor auth options change).

### 2. Repository onboarding by link
User pastes a clone URL → platform detects host type from a registry (or asks) → user picks a connection → `git ls-remote` with that credential proves read access → managed clone (shallow/partial where allowed; LFS/submodules policy) → profile. **Authorization:** the shared clone cache never grants access by itself; every use re-checks that the run owner's connection can read the repo (cached with short TTL). Fetch/push use the owner's credential; commits are authored as the user (or a configured bot identity) so history is attributable.

### 3. Provider registries: code defines *types*, settings define *instances*
| Layer | Where | Who | Examples |
|---|---|---|---|
| **Adapter type** (protocol + capabilities) | code (Spring bean implementing the port, discovered via registry) | developers | `ANTHROPIC`, `OPENAI`, `GEMINI`, `OPENAI_COMPATIBLE`; `GITHUB`, `BITBUCKET_CLOUD`, `GITLAB`; `JIRA_MCP` |
| **Provider instance** (configuration) | DB table, seeded from `devforge.providers.*` YAML, edited in **Settings → Admin** | admin role | enable/disable, display name, base URL (proxy, Azure/Vertex/Bedrock gateway, self-hosted Git), model allowlist/denylist, tier overrides, custom model entries, pricing, default model, OAuth app client id (secret via `SecretResolver`) |
So adding/updating/removing a **configuration** (new OpenAI-compatible endpoint, a self-hosted GitLab, hiding a model) needs no deploy. A genuinely **new protocol** needs a code adapter (`integration-adapter` skill) and is picked up automatically. Removing an instance in use is soft: existing runs keep their recorded provider/model; new runs can't select it; connections remain but show `PROVIDER_DISABLED`.

### 4. Build/runtime detection is data-driven
Repo profiles (Gradle, Maven, npm/yarn/pnpm, webpack, Vite…) come from a detector registry, not assumptions; sandbox images are selected from the detected toolchain (JDK version, Node version).

## Consequences
+ Any host/provider is a config entry or one adapter; permissions follow the real user; one vault, one audit story.
− OAuth adds app-registration and token-refresh complexity; per-user Git access means shared clone caches need authorization checks; admin-editable provider config needs validation and audit.
## Revisit when
Multi-tenant hosting (per-tenant OAuth apps and KMS keys), or enterprise SSO delegated tokens become available.

## Amendment: Git connections can be scoped to a repository
A project may span several repositories, and a repository- or workspace-scoped access token only works for what it was created for. A Git-host `Connection` therefore has an optional `scope` (`owner/name`, `owner/*`, or empty = any repository). A person may hold several connections for one host; publishing picks, per repository, the verified connection with the most specific match (exact, then `owner/*`, then unscoped). Scope narrows where a stored credential is *used*; it does not grant anything, and the host still enforces what the token can do. Other connection kinds cannot be scoped.

## Amendment: the target branch belongs to the project, not the credential
Where a pull request lands is policy for the repository within a project, so it is stored on the project's link to the repository (`workspace_repo.target_branch`), with the project-wide `workspace_publish.target_branch` as the default and the host's default branch as the last resort. A credential is per user and can serve repositories that merge into different branches, so putting the branch on it would let two people send the same project's changes to different places. The publish plan records which level supplied each branch (`baseSource`), so an approver sees when nothing was configured. Connections themselves are editable in place (label, scope, replacement token).
