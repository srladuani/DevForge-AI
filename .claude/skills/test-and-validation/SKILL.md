---
name: test-and-validation
description: Select, run, and honestly report tests, linters, builds, and quality gates for a change; diagnose failures. Use before declaring any work complete, after fixes, and when asked "does it pass / does it build".
argument-hint: "[scope or changed files]"
---

# Test & Validation

## Purpose
Establish what is actually true about a change's quality, and report it without embellishment.

## Workflow
1. **Detect** the toolchain from the repo (`pom.xml`/`mvnw`, `build.gradle`, `package.json` scripts, karma/vitest/jest config). Never assume commands; read the scripts. Prefer the project's wrapper.
2. **Select scope** from `git diff --stat`: changed modules → targeted tests first, then the full gate in `docs/engineering/testing-strategy.md`.
3. **Run** in order, cheapest first: lint/format → typecheck/compile → unit → integration → build. Capture exit codes and the failing output. Target-repo code must run in the sandbox, not on the host.
4. **On failure**: read the real error; classify as (a) caused by the change, (b) pre-existing/flaky (verify by running on the base commit or re-running once), (c) environment (missing tool/service). Fix (a); report (b)/(c) separately. Never delete, skip, or loosen a test to get green without saying so.
5. **Check test quality**: a new/changed behaviour has a test that fails without it; assertions are meaningful; no network/time/order dependence.
6. **For AI-workflow changes**: run the eval/regression set; compare against baseline; note any citation-accuracy drop.
7. **Report**.

## Output
```
Commands run: <exact> → exit code, duration
Passed / Failed / Skipped counts (from output)
Failures: test, error excerpt, classification, proposed action
Not run: <what and why>
Verdict: ready / not ready, with reasons
```

## Rules
- Do not claim "tests pass" unless you ran them and read the output in this session. Say "not run" otherwise.
- No hidden retries to mask flakiness; record any retry.
- Do not print secrets from env/test output.

## Done when
Every gate relevant to the change is either run-and-reported or explicitly listed as not run with a reason.
