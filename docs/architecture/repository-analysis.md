# Repository Analysis

## Registration and access
- **Register**: the user supplies a **clone URL** (or an existing local path) and picks one of their Git connections. The platform probes (`ls-remote` with that credential), detects host type, then clones into the managed store. Access is re-verified for the run owner on every use — a shared clone cache never grants access by itself (ADR-0013). Platform verifies it is a git repo, records default branch, remote, and HEAD.
- **Never work in the user's checkout.** Investigations read a **managed clone/`git worktree` pinned to a SHA** (`base_sha` recorded in the run). Implementation uses a new worktree on branch `devforge/<key>-<slug>`. Reads are therefore reproducible and evidence stays valid.
- **Path-jail**: all file ops resolve against the repo/worktree root with canonicalisation; reject `..`, absolute paths, symlinks leaving root, `.git` internals (except via git tools), and denylisted files (`.env*`, `*.pem`, `id_rsa*`, keystores, `application-*prod*`, secrets dirs—configurable). Denied reads return a "restricted" marker, and are audited.
- **Multiple repos per project and per run are a Phase 1 requirement** (e.g. Java backend + React frontend). Tools take `repoId`; each repo's profile **and role** are in the prompt; the user picks which repos a run may touch.
- **Cross-repo tracing:** the investigator follows contract seams between repos — frontend API client calls (URL/method, generated clients) ↔ backend `@RequestMapping` routes, shared DTO/field names, config keys, event names. Each hop is a separate evidence item tagged with its repo; a finding can cite evidence in several repos. Optional `hints` (e.g. "web calls backend via `/api/v1`") seed the search. If a seam can't be matched with evidence, the link is `HYPOTHESIS`.
- Per-run concurrency lock is per repo (one writer worktree per repo); a fix touching two repos creates one worktree/branch per repo with the same branch name, validated independently, and PRs that reference each other.

## Repo profile (deterministic, cached per HEAD SHA)
Detected without an LLM:
| Signal | Detection |
|---|---|
| Languages | file extension histogram |
| Build tool | `pom.xml`/`mvnw`, `build.gradle(.kts)`/`settings.gradle(.kts)`/`gradlew`/`gradle/wrapper`, `package.json` + lockfile type, `webpack.config.*`/`vite.config.*` |
| Test command candidates | Gradle: `./gradlew test` (`--offline` in the test step); Maven: `./mvnw -B test`; npm: `scripts.test`; karma/jest/vitest config presence |
| Modules / entry points | Maven modules, Spring `@SpringBootApplication`, `@RestController` count, React `src/index`, routers |
| Repo instruction files | `CLAUDE.md`, `README*`, `CONTRIBUTING*` — included as **untrusted guidance** (summarised; never allowed to change policy) |
| Size | file count/LoC → picks strategy limits |
Detectors are a registry (Gradle, Maven, npm/yarn/pnpm, webpack, Vite, …) so new ones are additive. Also detected: JDK/Node version (toolchain files, `sourceCompatibility`, `.nvmrc`, `engines`) to pick the sandbox image. Examples seen so far in test projects: a webpack + karma + antd React app (`npm test`) and a Gradle-built Spring backend. The platform must never assume Vite/Vitest or Maven.

## Repo-supplied instructions (tighten-only)
Files like a repo-level `CLAUDE.md`/`CONTRIBUTING` are read into the profile and shown to the user. They are untrusted: they may **add** restrictions (e.g. "analyse first, never push, commit only when asked") which DevForge adopts as stricter policy for that repo, but can never grant permissions, change tools, or relax approvals. Conflicting or suspicious directives are surfaced, not followed.

## Discovery tools (read-only, bounded)
`repo.tree(path, depth)`, `repo.glob(pattern)`, `repo.grep(regex, pathGlob, context)` (ripgrep; capped matches), `repo.read(path, startLine, endLine)` (≤ 400 lines/call), `repo.outline(path)` (class/method signatures; Java via JavaParser, TS via tree-sitter—added when needed), `repo.gitLog(path, n)`, `repo.gitBlame(path, range)`, `repo.diff(base, head)`.

## Tracing cross-file paths
Agent follows anchors: UI route/component → API client call → endpoint path → controller → service → repository/SQL; and in reverse via `grep` for callers. For Spring: annotations (`@RequestMapping`, `@Transactional`, `@Query`), bean wiring by type name. Each hop recorded as an evidence item; the final answer must present the chain in order. Call-graph indexing is the deferred upgrade (ADR-0007).

## Large repos
Respect `.gitignore`, skip vendored/build dirs (`node_modules`, `target`, `dist`), cap per-tool output, rank grep results by path heuristics (src over test over generated), reuse profile, summarise. Shallow/partial clone is allowed for investigation (history tools degrade explicitly with "history unavailable").

## Controlled modification
Edits only inside a run's worktree through `worktree.applyEdit(path, oldText, newText)` / `createFile` (exact-match replacement, fails on ambiguity, size-limited) — produces a patch; protected paths (CI config, build scripts, lockfiles, `.git*`) require explicit mention in the approved proposal. After each edit the server records the diff; final unified diff is hash-bound to approvals.

## Evidence retention
Evidence items store `repoId, sha, path, lines, contentHash, ≤40-line excerpt`. The UI "evidence viewer" fetches the file at `sha` from the managed clone (so excerpts need not be long-lived). If the sha is gc'd or repo unavailable, the evidence is shown as `STALE`. `verified=true` only after server-side recheck.

## Failure cases
Repo missing/dirty/locked → run fails at CONTEXT_GATHERING with actionable error; unsupported stack → generic search tools only, profile says `unknown`, validation unavailable (reported, not guessed).
