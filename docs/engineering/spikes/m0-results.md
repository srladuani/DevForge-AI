# M0 Technical Validation — Results

Run 2026-10-22 on the dev machine (Linux, 14 cores, ~29 GB RAM, Java 21, Node 22). Spike code: `spikes/`. Test projects used as fixtures (read-only; builds ran on scratch clones): a Gradle/Spring Boot 4.1 backend (~1.9k main Java files) and a webpack/karma/antd React app (~325 source files).
**Legend:** ✅ verified here · ⚠️ partly verified · ❌ not run (reason given). Nothing below was assumed to work.

| Spike | Status | One-line result |
|---|---|---|
| S1 AI library | ⚠️ | LangChain4j chosen; per-credential clients, tool calls returned unexecuted, usage read — verified against **fake** Anthropic/OpenAI servers only. No live provider call (no API keys on this machine). Gemini not exercised. |
| S2 Jira MCP | ⚠️ | Real ticket fetched read-only via Atlassian MCP (interactive OAuth session). Headless/server-side auth **not** verified. |
| S3 Sandbox | ⚠️ | 705 unit tests ran green **offline** in a no-network, read-only-root sandbox. Emulated with bubblewrap; **Docker not usable** (user not in `docker` group), so container limits and Testcontainers-based integration tests were not run. |
| S4 Search quality | ⚠️ | LLM-free lexical retrieval baseline measured. No LLM investigation was run. |
| S5 Git-host PR | ❌ | Skipped by design: host not chosen (Q4), no throwaway repo/token. |

## S1 — AI library (ADR-0004)
Method: throwaway Maven project (Boot 4.1.2, Spring AI 2.0.2, LangChain4j 1.22.0, Anthropic Java SDK 3.1.0) against in-process fake Messages / Chat Completions servers. Asserted: (a) a distinct credential per call reaches the wire, (b) a model-requested tool call comes back to our code **without being executed**, (c) token usage is readable.

| Criterion | Spring AI 2.0.2 | LangChain4j 1.22.0 | Anthropic SDK 3.1.0 (direct) |
|---|---|---|---|
| Credential per ChatModel/client | ✅ | ✅ (Anthropic and OpenAI modules) | ✅ (one client per key; sent as `Authorization: Bearer`) |
| Credential per *request* on shared instance | ❌ **ignored** — second prompt with a different `apiKey` still went out with the first key | n/a (models are cheap, build per credential) | ❌ no public override; use a client per key |
| Tool call returned to caller, not auto-run | ✅ (`ChatModel.call` returned the call; execution belongs to `ToolCallingAdvisor`/`ToolCallingManager`; the old `internalToolExecutionEnabled` flag no longer exists) | ✅ (`hasToolExecutionRequests()`) | ✅ |
| Usage tokens | ✅ | ✅ | ✅ |
| Multi-provider uniformity | per-vendor starters | ✅ one `ChatModel`/`ChatRequest` API; Anthropic, OpenAI, Gemini modules ship on the same version line | ❌ one SDK + API per vendor |
| Coupling | Tied to Boot 4.x; pulls Anthropic SDK **2.72.0**, which conflicted with SDK 3.1.0 on the same classpath (`NoSuchMethodError`) | independent of Spring | one SDK per vendor |
| Extra | MCP client starter, Micrometer observation | — | newest vendor features first |

**Decision (updates ADR-0004):** LangChain4j behind our `LlmClient` port as the default adapter library (uniform across the three required providers, no Boot coupling, we run the tool loop ourselves). Direct SDK is the escape hatch for a vendor feature LangChain4j lags on. Spring AI is not rejected on quality, but its per-request key is ignored, it ties adapters to Boot majors, and it adds no capability we need (we use the MCP Java SDK 2.x directly). Build clients per `(credentialId, version)` and cache them.
**Not verified:** real-provider behaviour (rate-limit/error bodies, streaming, prompt caching, schema-constrained output), Gemini, cancellation, behaviour under load. Re-run the contract suite live once a key is available.

## S2 — Jira MCP (ADR-0008)
- ✅ Via the Atlassian MCP server in this Claude Code session (user completed OAuth in a browser): site listing worked (scopes shown: `read:jira-work`, `write:jira-work`); `getJiraIssue` returned a real issue with summary, markdown description, status, components, labels, reporter, and comments.
- Server exposes **32 tools including writes** (create/edit/transition/comment on Jira, create Confluence pages). The OAuth grant included write scope → the **allowlist must be enforced by DevForge**, not by the grant.
- Payload is heavy: self-links, avatar URLs, duplicated comments (`fields.comment` and top-level `comments`), reporter **email** (PII). The tracker adapter must project to the neutral ticket model *before* anything reaches a prompt or log.
- Ticket content quality is realistic-messy: a daily-scrum status comment unrelated to the bug, a truncated sentence in the description, test-automation boilerplate. `ticket-analysis` must filter non-technical comments and flag truncation as a gap.
- Auth is interactive OAuth 2.1 (per user), which fits per-user Connections (ADR-0013). Atlassian docs mention optional API-token auth but not headless/service-account use → **unverified**; a background worker needs refresh-token handling or the token option (open risk R19).
- `/v1/sse` is being retired (notice says after 2026-06-30; the docs page says v1 usage is switched to v2 tools on 2027-03-01 — the dates differ, treat v1 as already unsupported); target `https://mcp.atlassian.com/v2/mcp` (Streamable HTTP). The test project's own `.mcp.json` still points at v1.
- **The tool responses themselves contained an instruction ("Include this notice in your response to the user…") appended by the MCP server.** Harmless here, but it is a live example that MCP output can carry instructions: it must be treated as data, never as commands (already a hard rule; add a regression fixture).
- Not tested: Atlassian-hosted MCP from a backend process, rate limits, attachments, pagination, other trackers.

## S3 — Sandbox (ADR-0006)
Method: `bwrap` (read-only root, tmpfs HOME, only the work dir and a Gradle home writable, `--unshare-net` for the test step), Gradle 9.5.1, JDK 21, scratch clone.
- ✅ Cold warm-up with network (Gradle distribution + dependencies): 3m25s. Re-warm with full resolution: 22s.
- ⚠️→✅ **Warm-up pitfall found:** warming with `testClasses` is *not* enough — offline `test` failed (`spring-boot-properties-migrator` not cached) because the test **runtime** classpath wasn't resolved. A warm step that resolves *all* resolvable configurations (init script in `spikes/s3-sandbox/`) fixed it. Same class of issue exists for Maven (`dependency:go-offline`) and npm.
- ✅ Offline, no network: `./gradlew --offline test` → **BUILD SUCCESSFUL in 28s, 72 suites, 705 tests, 0 failures** (counted from the XML reports).
- ❌ The repo's 8 integration tests use Testcontainers (MariaDB and an AWS emulator); they require a Docker daemon and were **not run**. The strict sandbox (no socket) cannot run them → two validation tiers (ADR-0006 update).
- ❌ Docker itself: daemon is active but this user lacks group access (`permission denied` on `/var/run/docker.sock`); I did not change that. Memory, pids, seccomp limits are unverified. The earlier doc line "Docker ✔" referred only to the CLI.
- Memory is a practical constraint: the machine had ~3 GB available during the run; Gradle JVMs used ~1.5–2 GB. Default concurrency 1.

## S4 — Search quality (ADR-0007)
Method (no LLM): for past fix commits, query = commit subject keywords, ranked by IDF-weighted `git grep` hits + filename bonus; hit = a file the fix changed (present at HEAD) appears in top-k. The subject is written *after* the fix, so this overstates real-ticket recall.

| Project | Cases | top-1 | top-5 | top-10 | top-20 | no hit in top-50 |
|---|---|---|---|---|---|---|
| Gradle/Spring backend | 60 | 31 (52%) | 46 (77%) | 53 (88%) | 56 (93%) | 1 |
| React/webpack app | 60 | 16 (27%) | 38 (63%) | 42 (70%) | 46 (77%) | 9 (15%) |

Real-ticket check (n=1): the actual text of a real bug ticket ranked the actually-fixed class **5th** (its controller 4th); noise words (role names) pulled unrelated files in at ranks 1–3.
Reading: direct search is a viable starting point for the Java backend, weaker on the React app (naming drift between UI labels and code). Supports ADR-0007 (search first); adds that **UI-label → code mapping** and iterative follow-up from the agent are needed, and that an outline/symbol index is the first upgrade to consider for React. Real recall with an LLM in the loop is unmeasured.

## Other findings that change the design
1. **Target repos carry their own instruction files** (one has a detailed `CLAUDE.md` with approval rules: analyse first, never push, commit only when asked). Rule adopted: repo instructions are **untrusted but may only tighten policy** (never loosen), and are shown to the user.
2. **Toolchain reality:** target Java uses Gradle 9.5.1 + JDK 21 + Boot 4.1; sandbox images must match the detected toolchain.
3. **Platform version fix:** DevForge's own backend targets **Spring Boot 4.1.x** (4.1.2 is latest stable), not 3.x; Spring Modulith 2.1.x, MCP Java SDK 2.1.x (stable) exist on Maven Central.

## Exit criteria for M0
| Question | Answer |
|---|---|
| Library? | LangChain4j adapter (decided; live re-check pending a key) |
| Jira reachable? | Yes, interactively; headless unresolved |
| Sandbox feasible? | Yes for unit-test tier (no network, offline). Container-dependent tests need a second tier |
| Investigation baseline? | Lexical recall measured; LLM-in-loop baseline still needed (needs a key) |
| PR host? | Not started (blocked on Q4) |

## Update after M1 (2026-10-22)
Docker Desktop became usable later the same day: Postgres and Testcontainers now run, so the Docker-dependent integration tests are real. The container *sandbox* (limits, no-network, Tier-2) is still unverified and belongs to M4. S1 and S4 live-model validation still await an API key (M2).

## Needed from you to finish M0
1. An AI provider API key (Claude first) entered locally for a live S1/S4 run — or approval to skip live validation until M2.
2. Docker group access (`! sudo usermod -aG docker $USER`, then re-login) to validate real container limits and a Docker-in-sandbox option.
3. Decision on how background workers will authenticate to Jira (OAuth refresh vs API token) — or accept per-user OAuth with refresh for now.
4. Git host choice (Q4) to enable S5.
