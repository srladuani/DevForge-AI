# Risks, Assumptions, and Open Questions

## Open questions (need a human answer; defaults let M0/M1 proceed)
| ID | Question | Default if unanswered | Blocks |
|---|---|---|---|
| Q1 | ~~ProgressiveBeef backend location~~ **Not a product question.** Users supply repo links; ProgressiveBeef (Bitbucket, Gradle build) is only a possible test fixture. | Synthetic fixture repos (Gradle Spring + Maven Spring + React webpack + React Vite) | M1 fixtures |
| Q2 | Which Jira MCP server (Atlassian remote MCP w/ OAuth, or self-hosted)? How will the backend authenticate non-interactively? | Spike M0-S2 decides | M3 |
| Q3 | ~~Deployment model~~ **Decided:** one organisation per deployment; sessions are single-user, AI credentials per user (ADR-0012). Still open: is company SSO (OIDC) available? | OIDC-ready; local-user dev profile | M1.3 |
| Q4 | ~~Git host~~ **Decided: open/pluggable.** Auth = API key/PAT first, OAuth second. Remaining: which host adapter ships first (Bitbucket Cloud likely, given the first test project) and which token types that host currently supports (verify in spike S5; vendor auth options change) | PAT adapter for the first host | M5 |
| Q5 | Which AI providers/models may the organisation use with its source code; retention terms? | Users bring own keys for Claude/OpenAI/Gemini; workspace allowlist policy; code excerpts only | M2 |
| Q11 | ~~First provider~~ **Decided: Claude first**; OpenAI and Gemini added later; providers configurable from settings or code (ADR-0013). Still to confirm per vendor: terms for API-key use in this product | Claude via API key | M2 |
| Q6 | Can golden Jira tickets/bugs be sanitized for evals? | Synthetic fixtures + a few manual cases | M2.5 |
| Q7 | Cost budget per run / per month? | $2/run soft cap, configurable | M2 |
| Q8 | Is the React Native app in Phase 1 scope? | No — profile detection only | — |
| Q10 | ~~Repo count~~ Not a product question: a project has 1..N repos, entered by the user. | — | — |
| Q9 | Does the team want GitHub PR support soon? | Later, via the same port | — |

## Needed from you to complete M0
1. An AI provider key (Claude) for a live S1/S4 run, or agree to defer live validation to M2. 2. Docker group access for the real container sandbox. 3. Headless Jira auth approach (R19). 4. Git host (Q4).

## Assumptions
A1-A5 (see `phase-1-requirements.md`). A6: Linux host with Docker available to the backend user. A7: Target repos' tests can run without production services (otherwise validation is limited and reported as such). A9-A10: see `phase-1-requirements.md`. A8: `fisrt-prompt.txt` is the authoritative product brief; filename typo left unchanged.

## Risk register
| ID | Risk | L | I | Mitigation | Owner/When |
|---|---|---|---|---|---|
| R1 | LLM misdiagnoses legacy code or fabricates references | H | H | Evidence ledger + server-side citation verification; label downgrade; golden evals; human review gates | M2 |
| R2 | Jira MCP unavailable/limited auth or tool set differs | M | M | Spike early; prompt-mode fallback; adapter port | M0 |
| R3 | Some user project builds can't run in a no-network sandbox (private registries, services, native deps) | H | H | Spike S3; deps-warm step with allowlisted proxy; report "validation unavailable" honestly; service sidecars later | M0 |
| R4 | Token cost / latency on large repos | M | M | Budgets, caching, model tiers, search-first, summarisation; track in usage ledger | M2 |
| R5 | Org policy forbids sending code to external LLM | M | H | Early answer to Q5; provider port; excerpt limits; path denylist; self-hosted option later | before M2 |
| R6 | AI framework API churn | M | M | Resolved direction: LangChain4j confined to adapters, pinned; direct SDK escape hatch (M0-S1) | M1 |
| R7 | Prompt injection leading to harmful action | M | H | Out-of-band policy, approvals, no network, jailed paths, injection evals | M2-M5 |
| R8 | Sandbox escape or resource abuse | L | H | Rootless, no socket/network, limits, conformance tests; microVM option | M4 |
| R9 | Scope creep toward Phase 2 / over-engineering (multi-agent, index) | M | M | ADR triggers; evidence-based additions only | ongoing |
| R10 | Host resource limits (RAM ~2 GB free observed) cause flaky local runs | M | L | Default concurrency 1-2; document needs | M1 |
| R11 | Worktree/disk growth | M | L | TTL sweeper, quotas | M6 |
| R12 | Estimation uncertainty (no repo/spike data yet) | H | M | Ranges, re-baseline after M0 | M0 end |
| R14 | Vendors' terms may not permit using consumer subscription sessions/tokens in a third-party product | M | H | Support API keys / enterprise-cloud auth only; legal check before adding other modes | before M2 |
| R15 | Quality, tool-calling and structured-output behaviour differ across providers/models | H | M | Capability declaration, per-provider evals, "supported = evaluated", hide failing models | M2b |
| R16 | User credential vault is a high-value target | L | H | Envelope encryption, master key outside DB, no-leak tests, audit, rotation, short in-memory lifetime | M1 |
| R17 | OAuth for Git hosts requires an admin-registered OAuth app per host and token-refresh handling | M | M | Ship API key/PAT first; OAuth after; per-host registration runbook | M5 |
| R18 | Per-user Git credentials vs shared clone cache could expose repos a user cannot read | M | H | Re-verify access per run owner (ls-remote, short TTL) before any read; test with two users | M2 |
| R19 | Atlassian MCP uses interactive OAuth; unattended background workers may not be able to authenticate/refresh | M | H | Verify refresh-token behaviour and the optional API-token mode; fall back to per-user token with expiry → `AWAITING_INPUT(CREDENTIAL)` | M3 | **M3 update:** built with a per-user credential (`email:token` as Basic, else Bearer); a rejected credential pauses the run for the owner to fix. Whether Atlassian accepts API tokens on the MCP endpoint, and refresh-token OAuth for unattended work, are still unverified.
| R20 | Container-dependent tests (Testcontainers) cannot run in the strict sandbox | H | M | Validation Tier 2 (opt-in DinD/runner) and honest "not run" reporting (ADR-0006) | M4 |
| R21 | MCP/tool output carries instructions (observed) | H | M | Data-only handling, allowlist, injection fixtures | M2 |
| R22 | The M1 simulation could be mistaken for real analysis | M | H | `[SIMULATED]` in every generated sentence, banners and badges in the UI, validations reported as "not executed", no cost shown. Remove only when real adapters replace it | M2 |
| R23 | Dev-auth header trust is unsafe if the "local" profile is ever exposed | L | H | Profile-gated, startup warning, documented; OIDC in M6 | M6 |
| R24 | Lexical search ranks the true cause low on large repos (observed on a real 2,000-file backend) | H | M | Tests down-ranked; M2 gives the model iterative tools and evals; consider outline/symbol index | M2 |
| R25 | M2 was built without a live API key: prompt, tool behaviour, error bodies, model ids and cost are unverified against the real service | H | M | Contract tests with a scripted server, replay evals, `LiveEvalTest` as the first thing to run with a key; ADR-0014 lists what is unverified | first live run |
| R26 | Real-model runs can spend a user's money | M | M | Hard limits (tokens/tool calls/turns/time), partial-result wrap-up, token counts on every run, per-run soft budget policy planned; no auto-retry on quota errors | M2 |
| R13 | Approval fatigue leads to rubber-stamping | M | M | Concise approval panel showing diff/commands/hash; optional separation of duty | M4 |

## Decisions pending review
All ADRs are *Proposed*. Highest-leverage to challenge: ADR-0012 (per-user credentials, supported providers), ADR-0003 (hybrid orchestration), ADR-0004 (library), ADR-0006 (sandbox), ADR-0007 (no index at first), ADR-0011 (auth model).
