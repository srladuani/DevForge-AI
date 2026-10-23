# Architecture Decision Records

Format: Status · Context · Options · Decision · Consequences · Revisit when. Supersede, don't rewrite history.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith, Spring Modulith-verified | Proposed |
| [0002](0002-maven-build.md) | Maven (wrapper) | Proposed |
| [0003](0003-orchestration-model.md) | Deterministic workflow + single tool-using agent loop | Proposed |
| [0004](0004-ai-integration-layer.md) | Own thin ports; LangChain4j adapters (M0 spike), MCP Java SDK | Accepted for M1–M2, live re-check pending |
| [0005](0005-persistence-and-jobs.md) | PostgreSQL + Flyway; DB-backed job queue | Proposed |
| [0006](0006-execution-sandbox.md) | Rootless Docker sandbox, no network by default | Proposed |
| [0007](0007-repository-analysis-strategy.md) | Direct search first; index only on evidence | Proposed |
| [0008](0008-mcp-and-integrations.md) | MCP client for Jira (read-only allowlist); provider-neutral tracker and Git-host ports (Git host chosen later) | Proposed |
| [0009](0009-frontend-stack.md) | Vite + React + TS + Tailwind/shadcn + TanStack Query; SSE | Proposed |
| [0010](0010-generic-run-model.md) | Generic `Run`/`RunInput` model for Phase 2 reuse | Proposed |
| [0011](0011-authn-authz.md) | OIDC-ready Spring Security; workspace-scoped RBAC; user-based sessions | Proposed |
| [0012](0012-user-sessions-and-agent-selection.md) | User-owned runs; per-user AI credentials vault; provider + model selection (Claude/OpenAI/Gemini) | Proposed |
| [0013](0013-connections-and-provider-registries.md) | Unified per-user Connections (AI/Git/tracker; API key first, OAuth second); repo onboarding by link; provider registries = code adapter types + admin-editable instances | Proposed |
| [0014](0014-investigation-loop-and-tool-policy.md) | Bounded read-only investigation loop, server-side tool policy, mode gating, named queues | Proposed (scripted-provider verified) |

All are *Proposed* until the planning review accepts them.
