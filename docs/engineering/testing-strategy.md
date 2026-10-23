# Testing Strategy and Quality Gates

## Test pyramid
| Level | Scope | Tools |
|---|---|---|
| Unit | state machine, policy evaluator, path-jail, evidence verifier, prompt builders, parsers | JUnit 5, AssertJ / Vitest |
| Slice/component | controllers, JPA repos (Testcontainers Postgres), React components | `@WebMvcTest`, `@DataJpaTest`, RTL + MSW |
| Integration | full workflow with fake `LlmClient` (scripted), fake MCP server, local fixture git repos, real Docker sandbox on a tiny fixture project | `@SpringBootTest`, Testcontainers |
| Contract | OpenAPI snapshot vs generated; frontend client regenerated and type-checked; MCP tool schema expectations | springdoc + diff, openapi-typescript |
| E2E | create run → findings → approve → diff → PR (mock provider) | Playwright |
| AI eval | golden tasks, replay + live | custom harness (JUnit tag `eval`) |
| Adapter contract | one abstract suite per port (`LlmClient`, `TrackerClient`, `PullRequestProvider`) run against every adapter + fakes; error mapping; capabilities | JUnit, WireMock |
| Security | credential vault (encrypt/rotate/no leakage in logs, DTOs, exceptions, prompts), run-owner enforcement, traversal/symlink, authz matrix (IDOR), approval hash binding, injection fixtures, sandbox escape attempts (no network, no host file) | JUnit, ArchUnit |
| Resilience | kill worker mid-step, lease expiry, duplicate submit, cancel during tool call | integration tests with fault injection |

## Quality gates (CI must pass; all required unless marked)
| Gate | Check |
|---|---|
| FE-1 | `npm run lint` (0 errors, warnings budgeted), `npm run typecheck` (strict), `npm test`, `npm run build` |
| BE-1 | `./mvnw -B verify`: compile with `-Xlint` + `-Werror` on our code, unit + integration tests, JaCoCo coverage ≥ 70% line on `domain`/`application` packages (guard, not target) |
| BE-2 | Modulith `verify()` + ArchUnit: no module cycles, no `internal` access, no JPA entities in `web` |
| API-1 | Generated `openapi.json` equals committed snapshot (intentional changes reviewed); FE client regenerates with no diff |
| SEC-1 | Secret scan (gitleaks) on repo and on every generated diff before push |
| SEC-2 | Dependency scan: `npm audit --omit=dev` and OWASP Dependency-Check/Trivy; fail on high/critical without justified suppression |
| SEC-3 | SAST (SpotBugs + find-sec-bugs; CodeQL if available) — *should* initially |
| AI-1 | Eval replay set passes thresholds: citation validity = 100%, no VERIFIED without evidence, schema-valid outputs ≥ 99%, injection fixtures cause 0 policy violations |
| AI-3 | A provider/model is "supported" only if it passes AI-1 on its own results; the adapter contract suite passes; no vendor SDK import outside adapter packages (ArchUnit) |
| AI-2 | Nightly live eval: recall/cost trend recorded; regression > 10% opens an issue (non-blocking) |
| TRACE-1 | Every FR in `phase-1-requirements.md` maps to ≥1 test (checked by a simple tag/report script) — should |
| EXEC-1 | Sandbox conformance test: no network, read-only root, memory limit kills, timeout kills, cannot read host paths |
| PR-1 | PR ready: gates above green, `diff-review` completed, description lists real commands/results, ADR/docs updated for architectural changes, no unrelated files |

## Test quality rules
Tests assert behaviour and failure paths, not mocks' internals. No H2 substitutes for Postgres. Fake LLM scripts are explicit fixtures. A bug fix ships with a test that fails before. Flaky tests are quarantined with an issue, not retried silently. Use `test-and-validation` skill; never report unrun tests as passing.

## AI evals
Dataset: `eval/golden/<case>/{input.json, repo-fixture or pinned sha, expected.json}`; expected = root-cause files/symbols, forbidden claims, required unknowns. Fixtures include: single-file bug, multi-hop (UI→API→service→SQL), misleading ticket, injection in ticket, injection in a repo README, missing-info ticket, no-bug-found. Metrics: file recall@3, evidence validity, unsupported-claim rate, tokens, cost, wall time, patch pass rate. Recording: `LlmClient` recorder/replayer keyed by prompt hash.

## Status as of M1
| Gate | State |
|---|---|
| BE-1/BE-2 | `./mvnw clean verify`: 114 tests on a real PostgreSQL; Modulith + boundary test. JaCoCo/ArchUnit vendor-import rule: **not yet added** |
| FE-1 | lint (0 warnings), strict typecheck, 47 tests, production build — all passing |
| API-1 | `OpenApiContractTest` (snapshot) + `ContractConformanceTest` (real responses vs. schema) + generated client check in `scripts/verify.sh` |
| EXEC-1, AI-1..3, SEC-1..3, PR-1 | not applicable yet or not yet automated (no sandbox, no real model, no CI host) |
| E2E | one real-browser journey (`frontend/e2e`), run locally via `scripts/verify.sh --e2e` |

**Practices that paid off in M1:** (a) *mutation checks* — deliberately breaking 7 safety rules (approval hash, owner-only credentials, vault binding, denylist, evidence downgrade, cancel handling, hash check) and confirming a test fails each time; run `./mvnw clean` after (stale classes otherwise hide the restore); (b) a *dishonest-agent* test that proves the server, not the agent, decides what is verified; (c) conformance of real responses to the published schema; (d) tests found real bugs: read off-by-one at end of file, approval-expiry rolled back by its own error, auto-approval re-requested after rejection, async-dispatch authorization breaking SSE, empty-id requests from the run page, test files outranking code.

## Added in M2
AI-1 is now a real gate: `ReplayEvalTest` (6 golden cases through the production loop and verifier; no unsupported trusted claim, honest citations all verify, dishonest ones are downgraded, no secret reaches the model, every injected escape is refused). Adapter contract tests (`AnthropicAdapterTest`, `AnthropicVerifierTest`) run against `ScriptedAnthropicServer`. `ArchitectureRulesTest` (ArchUnit) enforces vendor isolation and secret handling. `RealModeRunTest` runs the whole workflow with the real adapter against the scripted server. `LiveEvalTest` is the live-model measurement (opt-in). Mutation checks were repeated for the new safety logic (denylist/leak and trust downgrade are caught by the evals).
