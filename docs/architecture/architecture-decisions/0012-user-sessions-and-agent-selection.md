# ADR-0012: User-owned sessions, per-user AI credentials, provider and model selection
**Status:** Proposed (provider/credential generalisation: see ADR-0013)
## Context
Product requirement: a session is based on a single user; the AI provider tokens (Claude, OpenAI, Gemini, …) used for a project's tasks belong to that user. The user chooses the provider, then a model from that provider (e.g. Claude: Opus / Sonnet / Haiku tiers). Deployment: one organisation.

## Options
**Credential ownership**
1. Org/workspace-level shared key — simplest; loses per-user cost attribution and contradicts the requirement.
2. **Per-user credentials (BYOK) held server-side, encrypted** — matches requirement; background jobs can still run.
3. Browser-held keys sent per request — keys can't be used after the tab closes, so long-running/resumable jobs break; exposes keys to XSS.
**Credential type**
- API keys (all three vendors) or enterprise-cloud auth (e.g. Vertex/Bedrock/Azure) — supported by terms for application use.
- Consumer subscription logins/OAuth tokens (chat or CLI subscriptions) — vendor terms generally restrict use in third-party products; **not assumed supported**; verify per provider before offering (risk R14).
**Model choice**
- Hard-coded model lists (stale quickly) vs **catalog = config seed + live `list models` from the user's credential, filtered by capabilities** (tool calling, structured output, min context). Chosen: catalog.

## Decision
- **Session = Run owned by one user.** `Run.ownerUserId`, `Run.agent = {provider, modelId, fastModelId?, credentialId}` fixed at creation (switchable only at an approval gate, recorded in audit). Only the owner's credential is ever used for that run; other users may view/approve (per role) but cannot drive LLM calls on it.
- **UserCredential vault** (`identity` module): per user and provider: label, encrypted secret (AES-GCM envelope, master key from env/KMS via `SecretResolver`, key version for rotation), status (`UNVERIFIED|VALID|INVALID|QUOTA`), `lastVerifiedAt`. API is **write-only** (create/replace/delete/verify); secrets are never returned or logged. Verification = a minimal real call/`list models`.
- **Resolution at call time:** `LlmClient` receives a `CredentialContext` (decrypted in-memory for the call only) → per-request provider client. The adapter layer builds/caches clients keyed by credential id+version, never by secret value, and evicts on rotation.
- **Provider adapters** implement `LlmClient` for `ANTHROPIC`, `OPENAI`, `GEMINI`; each declares `ModelCapabilities` (tool calling, structured output mode, context window, prompt caching, streaming) so the agent loop adapts (e.g. schema-output via native mode vs tool-call emulation). Providers/models can be disabled by workspace policy (allowlist), e.g. to meet data-handling rules.
- **Model catalog** (`agent` module): `ModelInfo{provider, id, displayName, tier (STRONG|BALANCED|FAST), capabilities, contextWindow, pricing?}`. Seed file in config for tier labels/prices; ids confirmed by live listing; unknown/unsupported models flagged "no tool calling — unavailable". UI shows only providers the user has configured as selectable (others offer "Add key"). Example tiers: Claude Opus → STRONG, Sonnet → BALANCED, Haiku → FAST; OpenAI and Gemini tiers mapped analogously when their adapters are built (exact ids come from the catalog, not this document).
- **Step-to-model mapping:** the user picks one *primary* model; agent profiles declare a needed tier (`STRONG` for investigate/implement, `FAST` for summaries/classification). Default: all steps use the primary; if the user also picked a *fast* model, light steps use it. Cost estimate shown before start.
- **Missing/invalid/expired credential or quota exhausted mid-run:** step pauses into `AWAITING_INPUT(reason=CREDENTIAL)`, not `FAILED`; the owner fixes the credential and resumes from checkpoint. Credential errors are never retried blindly.
- **Cost/usage:** `ModelCall` rows carry `userId`, provider, model; budgets are per run and optionally per user per day.
- **Reproducibility:** run stores provider, model id, adapter version, prompt-template versions, so evals and incident review can reproduce behaviour. Eval suites run per supported provider; results are reported per provider/model, not averaged.

## Consequences
+ Per-user cost and accountability; no shared secret to leak; vendor choice per user. 
− Credential vault becomes high-value (envelope encryption, rotation, strict logging hygiene, security tests). − Quality varies across models; "supported" must mean *evaluated* (a provider ships only after passing the replay + live eval gates). − Three adapters multiply test and maintenance load; ship Claude first (or the user's first choice), others after the port is proven.
## Revisit when
A vendor offers a sanctioned delegated-auth flow for end users; multi-tenant hosting (then per-tenant KMS keys); org-managed shared keys are requested as an alternative (add as an optional second credential source, still attributed to a user).
