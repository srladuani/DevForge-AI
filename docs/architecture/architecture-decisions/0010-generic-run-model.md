# ADR-0010: Generic Run / RunInput model
**Status:** Proposed
## Context
Phase 2 must reuse everything but accept PRDs, specs, Figma, etc. A `JiraTicket`-centred model would not.
## Decision
Core aggregate `Run` (`type`: `SUPPORT_INVESTIGATION`, `SUPPORT_FIX`; later `PRODUCT_DESIGN`, `PRODUCT_DELIVERY`) with N `RunInput`s (`kind`: `JIRA_ISSUE | PROMPT | DOCUMENT | DESIGN_REF`, normalised payload JSON + source ref). Outputs are typed `RunArtifact`s (`FINDINGS`, `ROOT_CAUSE`, `FIX_PROPOSAL`, `PATCH`, later `REQUIREMENTS`, `ARCHITECTURE`, `TASK_PLAN`). Each artifact and evidence item has a stable UUID so a later `TraceLink(from, to, relation)` table can implement Requirement → Decision → Component → Task → Change → Test → PR without migrating Phase 1 data. Workflow definitions are data (step list + gates) per run type.
Above `Run` sits a **`ProjectType` registry** (`SUPPORT` now, `NEW_PROJECT` reserved/disabled). A project type declares accepted `RunInput` kinds, workflow definitions, agent profiles, artifact types and UI wizard steps; core modules stay type-agnostic. A project (`Workspace.kind`) owns 1..N repositories, each with a `role` and `hints`; a `Run` selects a subset. This is the mechanism behind the product's "choose Support or New project" entry point.
## Consequences
+ Phase 2 additive. − Slight indirection now; we deliberately skip building TraceLink and Phase 2 types until needed (YAGNI with a visible seam).
