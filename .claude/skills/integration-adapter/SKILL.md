---
name: integration-adapter
description: Add or change a provider adapter behind a DevForge port — an AI provider/model catalog (LlmClient), a tracker (TrackerClient), or a Git host (PullRequestProvider) — including capability declaration, credential handling, and contract tests. Use when adding Claude/OpenAI/Gemini support, a new tracker, a new Git host, or when changing a port.
argument-hint: "[port] [provider]"
paths: "**/integration/**, **/agent/**/adapter/**"
---

# Integration Adapter

## Purpose
Keep the core provider-neutral. Every external system is reached only through a port; adapters are the only code that imports vendor SDKs or knows vendor vocabulary.

## Before coding
Read ADR-0004, ADR-0008, ADR-0012 and `docs/architecture/api-and-data-model.md`. Confirm the port's current contract; changing a port needs an ADR update and updates to **all** existing adapters and fakes.

## Workflow
1. **Define scope**: which port (`LlmClient` | `TrackerClient` | `PullRequestProvider`), provider, minimum feature set, and what is explicitly unsupported.
2. **Declare capabilities** in the adapter (e.g. `ModelCapabilities`: tool calling, structured output mode, context window, prompt caching, streaming; tracker: comments/attachments/links; Git host: draft PRs, reviewers). Core code adapts to capabilities; it never branches on the provider name.
3. **Credentials**: receive a per-call `CredentialContext`/secret reference — never read env or config secrets directly, never cache by secret value (key by credential id + version), never log or include in exceptions/prompts. For LLM adapters the credential is the **run owner's** (ADR-0012).
4. **Map errors** to the port's error taxonomy: `AUTH_INVALID`, `QUOTA_EXHAUSTED`, `RATE_LIMITED` (retryable), `TIMEOUT` (retryable), `UNAVAILABLE`, `BAD_REQUEST`, `CONTENT_FILTERED`. Auth/quota errors must not be retried automatically.
5. **Normalise outputs** to core types (usage tokens, finish reasons, tracker-neutral ticket, PR result). Unmapped vendor data goes under an `extras` map, never into core fields.
6. **Model catalog (LLM)**: add provider seed (tier labels, optional prices), implement live `listModels` for the user's credential, mark models lacking required capabilities as unavailable. Do not hard-code model ids in core or docs as facts.
7. **Contract tests**: implement the shared abstract contract suite for the port (success, tool-call round trip, schema-output with repair, timeout, cancel, 401, 429, 5xx, oversized input, usage reporting) against a fake server (WireMock/local stub). Live-provider tests are opt-in (`-Plive`), skipped without a key, never in default CI.
8. **Registration**: adapter discovered via a registry bean; enabling a provider is config + workspace policy allowlist; UI lists it from the API, not hard-coded.
9. **Evaluation (LLM only)**: run replay + live eval suites for the new provider/model; record results per provider; a provider is "supported" only after meeting the gates in `docs/engineering/testing-strategy.md`.
10. **Docs**: update ADR/README tables, the supported-providers matrix, and `development-setup.md`.

## Security
Outbound hosts for an adapter come from admin config only (SSRF guard). Redact tokens from HTTP logs. Treat all vendor responses as untrusted data. No sample credentials in tests beyond obvious fakes.

## Done when
Contract suite passes against the fake; no vendor import exists outside the adapter package (ArchUnit rule); capabilities declared; error mapping tested; docs updated; for LLM adapters eval results recorded. Run `test-and-validation` and report what was and wasn't run.
