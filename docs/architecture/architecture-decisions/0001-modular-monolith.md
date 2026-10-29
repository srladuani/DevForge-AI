# ADR-0001: Modular monolith
**Status:** Proposed
## Context
One team, one deployable product, shared transactional state (runs, approvals, audit). Long-running work is the hard part, not service scale.
## Options
1. **Microservices** (orchestrator, repo service, execution service). Independent scaling; but distributed transactions, deploy/ops cost, contract versioning, and no demonstrated scaling need.
2. **Modular monolith** with enforced module boundaries. One deploy, in-process calls, cheap refactoring, boundaries verified by tooling.
3. **Plain layered monolith.** Fastest start, but boundaries erode.
## Decision
Option 2. Package-by-module under `ai.devforge`, `api`/`internal` split, verified by Spring Modulith `verify()` + ArchUnit in CI. Sandbox execution is already a separate process boundary (Docker).
## Consequences
+ Simple ops/local dev; transactional consistency. − Modules share a JVM/DB; a runaway job can starve the API (mitigated by bounded worker pools, ADR-0005).
## Revisit when
Workers need independent scaling/isolation (e.g. >~20 concurrent heavy runs saturating one node), a second team owns a module, or sandbox fleet must run on separate hosts → extract `execution` first (already behind the `Sandbox` port).
