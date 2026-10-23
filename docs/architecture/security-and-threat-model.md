# Security and Threat Model

## Assets
Source code (proprietary), Jira data (customer info possible), Git/Jira/LLM credentials, approval integrity, audit trail, host machine/network.

## Trust boundaries
```mermaid
flowchart LR
  B[Browser - untrusted client] -->|authn'd API| S[Backend - trusted]
  S -->|read-only, allowlisted| M[MCP/Jira - data untrusted]
  S -->|prompts: code snippets| L[LLM provider - external]
  S -->|jailed fs| R[Repo clones - content untrusted]
  S -->|docker API| X[Sandbox - hostile code]
  S -->|approved only| G[Git remote / PR]
```
Untrusted *content*: ticket text, comments, attachments, repo files/READMEs/comments, MCP output, dependency code, model output.

## Threats and controls
| # | Threat | Control |
|---|---|---|
| T1 | Prompt injection via ticket/repo ("ignore rules, exfiltrate .env, push to main") | Data-delimiting; permissions enforced outside model; path-jail + denylist; no network tools; approvals bound to artifact hashes; injection fixtures in evals; injection findings surfaced |
| T2 | Model self-escalates permissions | Policy is human-authored workspace config; plan text has no authority; tool executor checks profile ∩ policy ∩ approval state |
| T3 | Malicious build/test code escapes or exfiltrates | Rootless container, no network during tests, no secrets, read-only root, resource limits, no docker.sock, seccomp default, time limit, kill on cancel (ADR-0006) |
| T4 | Dependency fetch is a supply-chain vector | Separate deps step from lockfiles via allowlisted proxy/registry only; no install scripts executed outside sandbox; report new dependencies in review |
| T5 | Credential leakage (prompts, logs, UI, repo) | `SecretResolver` references only; secrets never enter prompts; log redaction filter; gitleaks in CI and on generated diff; no secrets to browser; sandbox env empty |
| T6 | Source code sent to LLM provider | Documented data-flow; provider/model **allowlist per workspace policy** (user cannot pick a disallowed provider; zero-retention/enterprise terms—open question Q5); denylist files never read; excerpt-size caps; option for self-hosted/VPC model later |
| T7 | Unauthorized repo/workspace access | RBAC per workspace/repo on every call; IDOR tests; tool context carries user+workspace |
| T8 | Unapproved external write / wrong target | `EXTERNAL_WRITE` needs fresh approval bound to patch hash + target branch; protected branches blocklist; force-push disabled; PR to `devforge/*` branch only; optional requester≠approver |
| T9 | Path traversal / symlink escape | Canonical path jail; tests with symlink fixtures |
| T10 | SSRF via MCP/URLs | Integration URLs from admin config only; outbound allowlist; no model-supplied URLs fetched |
| T11 | Audit tampering / repudiation | Append-only audit table (no UPDATE/DELETE grants), actor + hash chain optional later |
| T12 | Resource exhaustion (huge repos, runaway agent) | Budgets, concurrency caps, output caps, timeouts, per-workspace quotas |
| T13 | XSS from rendered LLM/ticket content | Render as text; sanitised markdown; CSP; no `dangerouslySetInnerHTML` |
| T14 | Sensitive data retention | Retention TTLs, prompts/transcripts off by default, deletion API per workspace |
| T16 | Theft/misuse of user AI credentials (DB leak, logs, XSS, another user driving someone's key) | Per-user vault with AES-GCM envelope encryption, master key outside DB (env/KMS), key versioning + rotation; write-only API; decrypt only in worker memory per call; never in prompts/logs/exceptions/DTOs (log scrubber + test); run owner check on every LLM-consuming action; revoke/delete wipes secret; credential use audited (no secret) |
| T17 | Cost abuse / runaway spend on a user's key | Per-run and per-user-per-day budgets, estimate shown before start, hard stop, usage visible to owner |
| T18 | Terms-of-service violation by using consumer subscription tokens | Only API keys / enterprise-cloud auth supported by default; any other mode needs explicit legal/vendor verification (R14) |
| T15 | Jira write misuse | Write tools disabled in MCP allowlist by default; comments only with approval |

Repo-supplied instruction files may tighten but never loosen policy (see `repository-analysis.md`). MCP/tool responses may contain appended instructions (observed in M0) and are always data.

## M6 review: where each threat stands (2026-10-24)
"Tested" means an automated test in this repository fails if the control is removed. "Not verified" means built but only exercised against fakes.
| # | Status | Evidence / gap |
|---|---|---|
| T1 injection | Tested | fencing, owner-only credentials, escape refusals in `InvestigationLoopTest`, `prompt-injection` eval, `RealFixRunTest` (out-of-scope edits refused) |
| T2 self-escalation | Tested | the implementer may edit only files of the approved proposal (mutation-checked); protected build/CI files only if named; secret files never |
| T3 hostile code | Partly | bubblewrap: no network outside the dependency step, hidden host paths, scrubbed env, CPU/process/file limits, timeout kills the namespace (`BubblewrapSandboxTest`). **No memory limit; no seccomp profile; Docker backend not built** |
| T4 supply chain | Partly | dependency step is lockfile-driven with install scripts disabled, **but network is unrestricted (no allow-list proxy)**. `scripts/audit-deps.sh` (osv.dev + npm audit): found and fixed 7 Jackson 2 records on 2026-10-24; run it before releases |
| T5 credential leakage | Tested | write-only vault, no secrets in events/metrics/responses (`ObservabilityTest`, `RealFixRunTest`), patch secret scan. **No secret scanning in CI (no CI exists)** |
| T6 code to the provider | Partly | per-project provider allowlist, denylisted files never read, excerpt caps. Provider data-handling terms (Q5) are a business decision, not code |
| T7 unauthorised access | Tested | `ApiAuthorizationMatrixTest` walks every documented operation: unauthenticated, stranger and non-admin cases, viewer writes |
| T8 unapproved external write | Tested | publication needs a PR approval bound to the plan hash; branch only, never forced, draft PR; foreign hosts never receive the token |
| T9 traversal/symlinks | Tested | path jail + symlink fixtures (`WorkCopyTest`, repository tests) |
| T10 SSRF | Partly | integration URLs come from admin config only; git/API hosts are matched before a token is sent. **Admin-entered base URLs are not restricted to public addresses** (an admin could point a provider at an internal host) |
| T11 audit tampering | **Not built** | audit rows are append-only by convention in code, not by database permissions or a hash chain |
| T12 exhaustion | Tested (partly) | per-run limits, per-project concurrency, per-person daily run and token quotas (`RunRetentionTest`); host memory not limited (see T3) |
| T13 XSS | Partly | rendered as text, no raw HTML in the UI; the API sends a restrictive CSP/no-store (`SecurityHeadersTest`). **The static UI host must set its own CSP** (not part of this repository) |
| T14 retention | Built | optional run retention (`devforge.workflow.retention-days`), sweeper for work copies and caches; per-workspace deletion API not built |
| T16 credential theft | Tested | vault encryption/binding, owner checks, `LocalProfileSafetyTest`; OIDC tokens are only held in browser memory |
| T17 cost abuse | Tested | per-run budgets and per-person daily token/run quotas; spend metric and alert (`devforge_model_tokens_total`) |
| T18 ToS | Documented | API keys only |
| T15 Jira writes | Tested | write tools cannot be called (`McpClientTest`) |
| New: sign-in | Tested / not verified | OIDC bearer validation (issuer, audience, expiry, signature, verified email) and admin sync in `OidcSignInTest`; **not verified against a real provider** |

Remaining security work before a real pilot: verify OIDC against the organisation's provider; decide the audit tamper-evidence level (T11); restrict admin-entered provider URLs (T10); add CI with secret scanning and `scripts/audit-deps.sh`; Docker/seccomp sandbox hardening (T3); set a CSP on whatever serves the UI.

## Approval matrix
| Action | Class | Default policy |
|---|---|---|
| Read ticket, search/read repo | READ | auto (within registered repos) |
| Create worktree, edit files in it | LOCAL_WRITE | requires `IMPLEMENT` approval |
| Run build/tests in sandbox | LOCAL_EXEC | requires `IMPLEMENT` approval; commands from reviewed list |
| Push branch | EXTERNAL_WRITE | `PUSH` approval, each time |
| Open PR | EXTERNAL_WRITE | `PR` approval, each time (may be combined with PUSH in one UI action, still recorded) |
| Jira comment/transition | EXTERNAL_WRITE | disabled by default; `JIRA_WRITE` approval if enabled |
| Merge, deploy, prod access | — | **never** in scope |

## Secure development requirements
Dependency scanning (OWASP Dependency-Check or `mvn versions`/Trivy; `npm audit`), secret scanning (gitleaks), SAST (Spring: SpotBugs + find-sec-bugs / CodeQL if available), security unit tests for path-jail/policy/approval hash binding, no default credentials, HTTPS and secure cookies/CSRF strategy for session auth, security headers.
