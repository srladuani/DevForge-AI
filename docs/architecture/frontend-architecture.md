# Frontend Architecture

Vite, React, TypeScript strict, Tailwind + shadcn/ui, React Router, TanStack Query, openapi-typescript/openapi-fetch (ADR-0009).

## Product shape
A dense **engineering workspace**: left rail (workspaces/repos/runs), main run view, right inspector. Not a chat page: the conversation is one panel of a structured run.

## Screens (Phase 1)
| Route | Content |
|---|---|
| `/projects/new` | Step 1 choose project type (cards: **Support / existing project**; **New project** disabled with "coming soon", from `GET /project-types`). Step 2 (support): add repositories — one or more rows, each with a **clone URL** (or path), the Git connection to use, role, optional hints; the "probe" call verifies access and shows the detected build tool/toolchain — then project integrations (ticket tracker — Jira today; optional Git host). Credentials are *not* project settings: each user adds their own AI/Git/tracker connections under `/me/connections`. Wizard steps are driven by the type definition so future types add their own |
| `/` | Run list for workspace: status chips, filters, new-run button |
| `/runs/new` | Input mode tabs (Ticket key or Prompt; tracker label from project integration), repository multi-select showing each repo's role, mode (Investigate / Propose fix / Fix + patch/PR), **agent selector: provider (Claude / OpenAI / Gemini — only configured ones enabled, others link to "Add key") → model (tier, context window, capability warnings, cost hint); optional fast model**, preview of normalised ticket |
| `/runs/:id` | Header (status, budgets, cancel/resume). Tabs: **Timeline** (steps, tool calls, live SSE), **Findings** (labelled claims + evidence chips), **Root cause**, **Fix proposal**, **Changes** (diff viewer), **Validation** (commands, results, logs), **Approvals**, **PR** |
| `/workspaces/:id/settings` | Repositories, integrations (status + verify), policy, usage/cost |
| `/me/connections` | "My connections": per provider (AI / Git host / tracker) add API key or PAT, or "Connect with OAuth"; write-only field, shows status + last verified, never the secret; lists models available to me |
| `/admin/providers` | Admin: add/update/disable/remove AI-provider, Git-host and tracker instances (base URL, model allow/deny, tier labels, pricing, OAuth app) |
| `/audit` | admin audit log |

Key components: `RunStatusBadge`, `EvidenceChip` → `EvidenceDrawer` (file at sha, highlighted lines, STALE indicator), `ConfidenceLabel`, `ApprovalPanel` (shows exactly what is approved + hash, diff summary, commands), `DiffViewer` (read-only, virtualised), `LogViewer` (virtualised, redacted), `EventTimeline`.

## Structure
```
frontend/src/
  app/ (providers, router, layout, error boundary)
  features/{runs,findings,approvals,workspaces,repos,integrations,audit}/{api,components,hooks,routes}
  components/ui/ (shadcn)
  lib/{api (generated), sse, format, a11y}
```
## State
- Server state: TanStack Query; keys `['runs', wsId, filters]`, `['run', id]`, …
- Live: `useRunEvents(runId)` opens SSE, applies events to the query cache via a reducer (`setQueryData`), reconnects with `Last-Event-ID`, falls back to polling on failure.
- Local UI state: component state/URL params. No global store initially.
- Mutations are command-style (`request-fix`, `decision`), idempotent, and surface server problem+json `code` as UI messages.

## Quality
Strict TS, ESLint (react-hooks, jsx-a11y), axe checks in component tests, Vitest/RTL/MSW, Playwright smoke (create run with mock backend → findings → approve). Visual language: neutral, high-contrast, light/dark, monospace for code/paths, never colour-only status.

## Boundaries
No orchestration logic, prompts, secrets, or direct integration (Jira/Git/LLM) calls in the browser. All text from LLM/ticket/repo is rendered as text.

## As built in M1
Routes: `/projects`, `/projects/new` (type chooser → name + repositories), `/projects/:id` (Runs, Repositories, People & policy), `/projects/:id/runs/new` (input, repositories, mode, **provider → model**), `/runs/:id` (Overview, Findings with evidence drawer, Fix proposal, Changes/diff + checks, Approvals, live Timeline), `/connections`, `/admin/providers`, `/admin/audit`. The UI renders only what the server says is allowed (`nextActions`, role) and the server re-checks everything. All model/ticket/repository text is rendered as text. Secrets are write-only password fields cleared after save. Simulated output is marked everywhere it appears.
