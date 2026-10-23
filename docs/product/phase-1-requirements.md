# Phase 1 Requirements

IDs are referenced by the implementation plan and tests. Priority: M = must (Phase 1), S = should, C = could.

## Functional
| ID | Requirement | P |
|---|---|---|
| FR-0 | On project creation the user chooses a **project type**: *Support / existing project* (enabled) or *New project* (shown, disabled, "coming soon"); type determines the setup wizard and available workflows | M |
| FR-1 | Create a project (workspace); register **1..N** git repositories (local path or URL), each with a role (`BACKEND`, `WEB_FRONTEND`, `MOBILE`, `LIBRARY`, `OTHER`) and optional stack/command hints; compute and show each repo's profile. Support projects require ≥1 repo; add/remove repos later | M |
| FR-1a | A run targets a chosen subset (1..N) of the project's repos; investigation tools, evidence, findings, fix proposals, patches, validation and PRs are **per repository** (a fix may yield one change set + PR per repo); cross-repo findings link evidence across repos (e.g. UI call → API endpoint) | M |
| FR-2 | Configure project integrations — ticket tracker (Jira via MCP now; port allows others) and optional Git host (none required: local branch + patch always works) — with secret references; verify connectivity and display real status | M |
| FR-3 | Create a run from a tracker ticket key (Jira now) **or** a prompt, with selected repos and a mode (investigate / propose fix / fix+PR) | M |
| FR-4 | Retrieve and normalise the ticket (tracker-neutral model; Jira first) (fields, comments, AC, repro, linked info, attachment metadata) read-only; show it before running | M |
| FR-5 | Detect and report missing/ambiguous information; allow user to answer or proceed | M |
| FR-6 | Investigate repos with read-only, path-jailed tools; produce findings labelled VERIFIED/SUPPORTED/HYPOTHESIS/UNKNOWN with evidence (path, lines, sha) | M |
| FR-7 | Produce a root-cause assessment with ranked hypotheses and confidence | M |
| FR-8 | Allow investigation-only completion; no code change required | M |
| FR-9 | Produce a fix proposal: recommended + alternatives, affected files, risks, test plan | M |
| FR-10 | Require approval before implementation per policy; approval bound to proposal hash | M |
| FR-11 | Apply changes in an isolated worktree/branch; produce a unified diff and summary | M |
| FR-12 | Run detected build/test/static-analysis commands in the sandbox; show real results/logs | M |
| FR-13 | Human review of diff and validation results; request changes (loops back) | M |
| FR-14 | Always produce a local branch + patch per changed repository. If a Git-host adapter is configured, then after approval push and open a PR **per changed repository**; display links; PRs cross-reference each other | M (branch + patch) / S (first PR adapter, host TBD) |
| FR-15 | Live progress (SSE/polling), cancel, resume after failure, restart-safe | M |
| FR-16 | Run history, search/filter, run detail with all artifacts | M |
| FR-17 | Token/cost usage per run and workspace | S |
| FR-18 | Audit log of user actions, approvals, tool calls, external writes | M |
| FR-19 | Role-based access per workspace | M |
| FR-20 | Export findings/fix proposal as Markdown | S |
| FR-21 | Post summary comment to the tracker (approval-gated, off by default) | C |
| FR-22 | Each user manages their own AI-provider credentials (add/replace/delete/verify; write-only; encrypted at rest) for Claude, OpenAI and Gemini | M (≥1 provider at M2; all three before Phase 1 exit) |
| FR-23 | At run creation the user selects agent provider, then model from that provider's catalog (live-listed, capability-filtered, tiered); optional fast model; choice recorded on the run | M |
| FR-24 | A run is owned by one user and uses only that user's credential; other members may view/approve per role but cannot drive LLM calls on it | M |
| FR-25 | Credential problems (missing/invalid/quota) pause the run for the owner to fix and resume; never silently fall back to another credential or provider | M |
| FR-26 | Workspace policy can allowlist providers/models and set per-run / per-user budgets; estimate shown before start | S |

## Non-functional
| ID | Requirement |
|---|---|
| NFR-1 | Security per `security-and-threat-model.md`; no secrets in prompts/logs/browser |
| NFR-2 | Evidence integrity: no `VERIFIED` finding without a server-validated evidence item |
| NFR-3 | Reliability: runs survive restart; no lost approvals; idempotent create |
| NFR-4 | Performance: UI interactive < 2 s; first progress event < 5 s; investigation of a medium repo typically < 10 min (to calibrate) |
| NFR-5 | Cost control: per-run budgets enforced; default cap configurable |
| NFR-6 | Observability: structured logs, metrics, traces, health checks |
| NFR-7 | Accessibility: WCAG 2.1 AA for core flows |
| NFR-8 | Maintainability: module boundaries verified in CI; ≥ defined quality gates |
| NFR-9 | Portability: Linux dev setup via documented commands; Postgres via Docker |
| NFR-10 | Provider independence: LLM, tracker, Git-provider swappable via ports |
| NFR-11 | Data handling: retention TTLs; transcripts off by default |
| NFR-12 | Credential protection: AES-GCM envelope encryption, master key outside DB, no secret in logs/prompts/DTOs (tested), rotation supported |
| NFR-13 | Provider parity: a provider/model is selectable only if it supports tool calling and passes the eval gates; results reported per provider |

## Input / output contracts
**Run input (API):** `{mode, inputs:[{kind: TICKET (tracker, ref) or PROMPT (text)}], repositories:[id], agent:{provider, model, fastModel?}, notes?}`.
**Normalised input:** `{title,type,observed,expected,reproSteps[],environment,identifiers[],acceptanceCriteria[],gaps[{text,blocking}],injectionFlags[],source{key,fetchedAt,updatedAt}}`.
**Findings output:** `{summary, findings[{label,text,confidence,evidence[]}], rootCause{hypotheses[{text,confidence,supporting[],contradicting[]}]}, unknowns[], nextSteps[]}`.
**Fix proposal:** `{rootCauseRef, recommended{description,files[],commands[],risks[]}, alternatives[], testPlan[], outOfScope[]}`.
**Change output:** unified diff + stats + `validation[]`; **PR output:** `{provider,url,branch,target}`. Schemas live in `backend/.../agent-profiles/schemas/` and are validated at runtime.

## Approval rules
See approval matrix in `security-and-threat-model.md`; approvals bind to `artifactHash`, expire, are recorded with approver identity; any edit invalidates.

## Failure scenarios (each needs a test)
Jira unavailable/unauthorized · ticket not found · empty/ambiguous ticket · repo missing/dirty · LLM timeout/rate-limit/invalid JSON · budget exhausted · cited file changed/absent · sandbox build fails/times out/OOM · tests fail pre-existing vs new · approval rejected/expired · push rejected (protected branch/auth) · PR provider error · worker crash mid-step · duplicate submit · user cancel mid-tool.

## Acceptance criteria (Phase 1 done)
1. On a real ticket from a test project (ProgressiveBeef first), an investigate-only run yields findings where 100% of citations resolve to existing files/lines at the recorded SHA, and the true root-cause file appears in the top-3 hypotheses on ≥ 60% of the golden set (target to be calibrated in M0).
2. Fix path: for ≥ 3 golden bugs, generated patch passes the project's tests in the sandbox and is accepted by a human reviewer with ≤ minor edits for at least some (metric tracked, not guaranteed).
3. No external write occurs without a recorded matching approval (verified by test and audit query).
4. Killing the backend mid-run and restarting resumes the run to a terminal/awaiting state.
5. Quality gates in `testing-strategy.md` all pass in CI.

## Assumptions
A1 Target repos are accessible locally/over git with credentials the operator provides. A2 A Jira MCP server reachable by the backend exists. A3 Git hosting is **unspecified**; Phase 1 must work with local branch + patch, and a PR adapter is added for the host the product owner picks. A4 Users accept source excerpts going to their chosen AI provider (workspace policy can restrict). A9 Users hold API keys or enterprise-cloud access for the providers they select; consumer subscription logins are not assumed usable. A10 One organisation per deployment; Java and React are the supported stacks. A5 Target builds can run offline after dependency warm-up. All tracked in `risks-and-open-questions.md`.
