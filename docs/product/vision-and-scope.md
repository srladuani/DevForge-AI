# Vision and Scope

**DevForge AI — From Issue to Solution.** An AI software-engineering platform that performs the engineering lifecycle on real repositories with evidence, validation, and human approval.

## Product model: projects and project types
DevForge is a generic, vendor-neutral product for teams with Java and React projects. It assists developers and, where policy allows, performs the fix/implementation work for them. The tracker (Jira today), Git host (open) and AI provider (Claude, OpenAI or Gemini, chosen per session) are all pluggable. Users create a **Project** and pick a **project type**:

| Project type | Status | Setup asks for | Workflow family |
|---|---|---|---|
| **Support / existing project** | Phase 1 — enabled | 1..N repositories, each with a role (backend, web frontend, mobile, library, other); tracker + Git provider integrations | investigate → propose fix → implement → validate → PR |
| **New project** | Phase 2 — visible, disabled ("coming soon") | requirements/specs/designs; optionally target repos | analyse → design → plan → build → review → PR |

Example (a support project, using ProgressiveBeef as the first test case): repo 1 = Java/Spring Boot backend (`BACKEND`), repo 2 = React admin portal (`WEB_FRONTEND`); React Native app added later as a third repo (`MOBILE`). A single run can target any subset of the project's repositories, and findings are tagged per repository so a bug that spans UI and API is traced across both.

Project types are plug-ins (input kinds, workflow, agent profiles, UI screens) over a shared core, so the second type is added by registration, not by changing the core. Nothing customer-specific is hard-coded.

## Sessions, AI providers and models
A run is a **session owned by one user** and runs on **that user's own AI-provider credentials** (API key / enterprise-cloud auth). The user selects an agent provider (Claude, OpenAI, Gemini) and then a model from that provider's catalog (for Claude: Opus, Sonnet, Haiku tiers; OpenAI/Gemini analogous). Only providers the user has configured are selectable, and workspace policy can restrict providers/models. Details and rationale: ADR-0012.

## Principles
Evidence over eloquence · humans approve consequential actions · least privilege · works on real repos with real build tools · one platform, two phases.

## Phases
| | Phase 1: Support engineering (now) | Phase 2: Product engineering (later) |
|---|---|---|
| Inputs | Jira issue (MCP), direct prompt, selected repos | PRDs, specs, architecture docs, wireframes/Figma, API specs, stories |
| Outputs | Findings, root cause, fix proposal, validated patch, branch + PR | Requirements analysis, designs/ADRs, task/estimate plans, implementation, tests, docs, PRs |
| Reused platform | — | repo mgmt, agent runtime, sandbox, approvals, audit, Git/PR, SSE UI, run model |
| New in Phase 2 | — | document/design ingestion, requirement & decision artifacts, `TraceLink` (Requirement→Decision→Component→Task→Change→Test→PR), planning/estimation workflows, multi-repo feature delivery |

## Scope exclusions (Phase 1)
Production/DB/log access · auto-merge/deploy · tracker writes by default · React Native support (profile detection only) · multi-tenant SaaS · fine-tuned models · autonomous multi-agent swarms · design/Figma ingestion · cost billing.

## Personas
| Persona | Needs |
|---|---|
| **Developer / Support Engineer** (primary; owns the session and its AI credentials) | Fast, trustworthy root-cause analysis of a ticket; clear evidence; a ready patch to review |
| **Senior Developer / Reviewer** | Inspect evidence and diff, challenge conclusions, approve or reject |
| **Tech Lead / Admin** | Register repos, configure integrations and policy, view cost/audit |
| **Security/Compliance** (secondary) | Confidence on data flow, approvals, audit |
| *Future:* **Product Owner / Architect** | Requirements → design → plan (Phase 2) |

## User journeys
1. *Ticket → diagnosis:* Engineer enters `PB-1234`, selects backend repo, sees normalised ticket and gaps, starts an investigate-only run, watches live progress, reads labelled findings with file/line evidence, copies the root-cause summary.
2. *Diagnosis → fix:* From findings, requests a fix proposal, reviews alternatives/tests, approves implementation, reviews diff + test results, approves push + PR, gets the PR link.
3. *Prompt-only:* Engineer types "Why does export time out for >1000 rows?", picks repos, gets findings; no Jira required.
4. *Degraded:* Ticket tracker unavailable → UI says so, offers pasted ticket.
5. *Admin:* adds repo, verifies Jira MCP, sets policy (e.g. require approver ≠ requester), reviews cost.
