---
name: fix-planning
description: Turn investigation findings into a minimal, maintainable fix proposal with alternatives, affected files, risks, and a test plan, ready for human approval. Use after repository-investigation and before editing code.
argument-hint: "[findings or issue]"
---

# Fix Planning

## Purpose
Propose the smallest change that resolves the verified root cause, with enough detail for a human to approve or reject. **No code edits in this skill.**

## Inputs
Findings from `repository-investigation` (with evidence and confidence), repo conventions, existing tests.

## Workflow
1. **Gate on evidence**: if the root cause is only `HYPOTHESIS`, say so and propose what verification (a failing test, log, query) comes first. Do not dress a guess as a fix.
2. **Define the target behaviour** and the acceptance check (what test proves it's fixed).
3. **Enumerate ≥2 approaches** where meaningful (e.g. guard at boundary vs fix at source). For each: change size, risk, blast radius, reversibility.
4. **Recommend one**, preferring: minimal diff, existing patterns, no unrelated refactors, no new dependencies, backward-compatible API/DB behaviour.
5. **List affected files/symbols** (`path:line`) and callers that could regress; check for other usages via search.
6. **Risk assessment**: data migrations, concurrency, authz impact, public API changes, performance, rollout/rollback.
7. **Test plan**: new/updated tests (name, level, what they assert), existing suites to run, manual checks. Include a regression test that fails before the fix when possible.
8. **Approval request**: explicit list of what will change and what commands will run in the sandbox.

## Output
```
Root cause (label + confidence)
Recommended fix (+ alternatives considered)
Files/symbols to change
Risks & mitigations
Test plan
Out of scope
Approval needed: yes/no, for what
```

## Done when
A reviewer could implement it without re-investigating; every file reference was verified; scope creep is explicitly excluded.

## Security
The plan cannot grant itself permissions (extra paths, network, commands). Anything beyond the workspace policy is listed as a request for human decision. Flag fixes that touch authn/authz, crypto, or secrets for extra review.
