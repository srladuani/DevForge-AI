# Phase 2 Roadmap (high level — not implemented now)

## Goal
Accept requirements, specs, designs, and existing products, then drive the work a team does: analysis → design → plan → implementation → tests → review → PR, with traceability.

## Reuse map
| Phase 1 capability | Phase 2 use |
|---|---|
| `Run` + `RunInput` + `RunArtifact` (ADR-0010) | new run types, new input kinds (`DOCUMENT`, `DESIGN_REF`), new artifacts |
| Workflow engine, job queue, SSE | longer, multi-stage workflows defined as data |
| Agent profiles, tool policy, evidence ledger | requirement analyst, architect, planner, UI implementer profiles |
| Repository tools, worktrees, sandbox | feature implementation across repos |
| Approval/audit | design sign-off gates, plan approval |
| Git/PR adapters | multi-PR delivery |

## Extension points to preserve now
- `RunInput.kind` and `RunArtifact.type` are open enums (strings validated by registry).
- Workflow definitions are data (step list, profile ids, gates) → add `PRODUCT_DESIGN` without engine change.
- Stable UUIDs on requirement-like and evidence objects; reserve `trace_link(from_type, from_id, to_type, to_id, relation)` migration for later.
- `TrackerClient` generalises to Jira/Linear/Azure Boards; `DesignSource` port reserved (Figma API/MCP).
- Evidence generalises beyond code: document span (doc id, section, quote hash).

## Candidate capability stages
1. **Requirements intelligence:** ingest PRD/spec; extract FR/NFR, assumptions, ambiguities; clarifying-question loop; acceptance criteria.
2. **Design:** options/trade-offs, ADR generation, data/API/security design, diagrams; consistency checks against existing codebase.
3. **Planning:** epics/stories/tasks, dependencies, estimate ranges with stated assumptions, sequencing, risk register.
4. **UI/UX:** wireframe/Figma → component plan → React implementation with visual checks.
5. **Delivery:** multi-task implementation, tests (unit/integration/E2E), review/security passes, docs, PR set, progress reporting.
6. **Traceability:** Requirement → Decision → Component → Task → Change → Test → PR graph with queries ("which tests cover REQ-12?").

## Deferred explicitly
`TraceLink` table, document parsers, Figma integration, estimation models, multi-agent sub-workflows, code index. Revisit after Phase 1 M5 and eval evidence. Phase 2 gets its own requirements doc and ADRs before any build.
