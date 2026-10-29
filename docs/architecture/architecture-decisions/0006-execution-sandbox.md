# ADR-0006: Isolated code execution
**Status:** Proposed
## Context
Building/testing target repos executes untrusted code and dependencies. Docker 29 is available locally.
## Options
1. Run on host — rejected.
2. **Rootless Docker container per validation step**, repo as a git worktree mounted into the container, resource-limited.
3. gVisor/Firecracker microVMs — stronger isolation, heavier ops; for multi-tenant/SaaS.
4. Remote CI runner — good for later, slower feedback.
## Decision
Option 2 behind a `Sandbox` port. Defaults: non-root user, read-only root FS, writable `/work` (worktree) and tmpfs, **no network**, CPU/mem/pids/time limits, no host mounts besides the worktree, no Docker socket, no host env vars, secrets never injected. Dependency resolution: pre-warmed per-repo dependency cache volume (Gradle `~/.gradle` caches, Maven `~/.m2`, npm/yarn/pnpm caches) populated by a *separate, network-enabled, allowlist-proxied "deps" step* using lockfiles; the test step then runs offline. Output captured with size caps and secret redaction.
Images are selected from the detected toolchain (JDK 8/11/17/21, Node LTS versions) from a maintained image matrix; the Gradle daemon is disabled or memory-capped in the sandbox.
### M0 evidence and amendments
- Verified (bubblewrap stand-in, not Docker): with network only in a *warm* step, a Gradle 9.5.1 project's **705 unit tests ran green offline in 28 s**. Docker itself was unusable on the dev machine (no group access), so container limits are unverified.
- **Warm step must resolve everything the test step needs** (all resolvable configurations / `dependency:go-offline` / `npm ci`), not just compile classpaths: warming with `testClasses` left the test runtime classpath missing and the offline run failed.
- **Two validation tiers.** *Tier 1 (default, strict):* no network, no docker.sock — unit tests and static analysis. *Tier 2 (opt-in per project, admin-enabled, shown in the approval panel):* tests needing containers (one test project's integration tests use Testcontainers with MariaDB and an AWS emulator) run in a rootless Docker-in-Docker / Sysbox runner or a separate runner host. If Tier 2 is not enabled, those tests are reported as **not run**, never skipped silently.
- `Sandbox` port gets a second backend: **bubblewrap** (Linux, rootless, no daemon) for Tier 1 when Docker is unavailable; Docker remains preferred for resource limits.
- **M4 implementation (2026-10-23):** the bubblewrap backend is the first one built because Docker was unusable on the dev machine. It gives namespace isolation, no network outside the dependency step, hidden host paths and CPU/process/file limits, but **no memory limit**; Docker remains the backend to add for that. The dependency step shares the network without an allow-list proxy yet. Toolchains (JDK, Node) are read-only mounts configured per server (`devforge.execution.toolchain-mounts`). Edits happen in a private `git archive` export, never in the user's checkout; the workflow only reaches the sandbox through the `Validator`.
## Consequences
+ Strong default containment. − Dependency caching is real work; some builds need services (DB) → compose sidecars later.
## Revisit when
Multi-tenant hosting or hostile-repo threat model → microVM runner (same port).
