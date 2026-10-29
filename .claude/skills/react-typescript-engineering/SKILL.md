---
name: react-typescript-engineering
description: React + TypeScript (strict) conventions for the DevForge workspace UI (feature structure, typed API client, TanStack Query, accessibility, testing). Use when writing or modifying frontend code, or reviewing React code in a target repo.
paths: "**/*.ts, **/*.tsx, **/vite.config.*, **/package.json"
---

# React & TypeScript Engineering

## Purpose
Build a dense, accessible engineering workspace UI (runs, evidence, diffs, approvals), not a marketing page or generic chat.

## Before coding
Read `docs/architecture/frontend-architecture.md`. For a *target* repo (e.g. a webpack + antd + karma app) follow its stack and conventions instead of ours.

## Structure
```
src/app/        providers, router, layout
src/features/<feature>/{api,components,hooks,routes,types}
src/components/ui/   shadcn primitives
src/lib/        api client (generated), utils
```
Features import from `components/ui` and `lib`, never from each other's internals.

## Rules
- `strict`, `noUncheckedIndexedAccess`; no `any`, no `as` on API data. Types come from the generated OpenAPI client; validate untrusted shapes with zod only where the contract is loose.
- Server state: TanStack Query (stable query keys per feature, invalidate on mutation). Client/UI state: local state or URL params first; add a store only with a demonstrated need.
- Live run progress: SSE hook with polling fallback; reconcile into the query cache. UI never holds orchestration logic.
- Components: presentational vs container split; small, props typed, no business rules in JSX. Render states explicitly: loading, empty, error, partial, stale.
- Evidence UI: every finding links to file/line; label badges `VERIFIED/SUPPORTED/HYPOTHESIS/UNKNOWN`; diffs rendered read-only with approve/reject controls bound to server-side approval ids.
- Accessibility: semantic elements, keyboard navigation, visible focus, ARIA only when needed, color never the only signal, contrast AA.
- Security: no secrets in bundle or `localStorage`; render LLM/ticket/repo text as text (never `dangerouslySetInnerHTML`; sanitize markdown); no tokens in URLs.
- Agent selection UI: provider list shows only providers the user has configured (others offer "Add key"); the model list is fetched from the API for the chosen provider and shows tier, context window and capability warnings. API-key inputs are write-only password fields: never re-display, store, or log a key client-side; show only status and last-verified time. Run headers show provider + model.
- Performance: route-level code splitting; virtualise long logs/diffs.

## Testing
Vitest + React Testing Library (query by role/label), MSW for API, Playwright for critical flows (create run → see findings → approve). Test behaviour, not implementation.

## Done when
`npm run lint && npm run typecheck && npm test && npm run build` all run and pass; new UI states checked in a browser when feasible (say so if not).
