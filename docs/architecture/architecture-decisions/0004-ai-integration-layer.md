# ADR-0004: AI integration layer
**Status:** Accepted on spike evidence (`docs/engineering/spikes/m0-results.md`) and implemented in M2 (Claude adapter, LangChain4j confined to `agent.internal.anthropic`, vendor retries disabled); live-provider re-check still pending a key
## Context
Need provider independence, tool calling, structured outputs, usage/cost tracking, MCP client, cancellation/timeouts.
## Options
1. **Spring AI** — idiomatic Boot config, ChatClient/tool calling, structured output, MCP client starter, observability hooks. Abstractions evolve quickly (verify current version/API in spike).
2. **LangChain4j** — mature Java-native, AI Services, broad provider support, own tool/MCP support; less Spring-native, different conventions.
3. **Provider SDKs directly** (e.g. Anthropic Java SDK) behind own port — maximum control, more code per provider.
## Decision
Define our own narrow ports: `LlmClient` (messages + tools + response schema → response + usage), `ToolRegistry`, `ToolExecutor`. The agent loop, persistence, budgets, approvals and policy are **ours** (no library provides persisted, resumable, approval-gated workflows). Implement `LlmClient` adapters with **LangChain4j** (Anthropic, OpenAI, Gemini modules, one API and one version line); only adapter packages import it (ArchUnit rule). Use the MCP Java SDK directly for the Jira client. Direct vendor SDK is the escape hatch for a feature LangChain4j lags on.
**Spike evidence (fake servers, no live key):** all three candidates return model-requested tool calls to the caller unexecuted, expose usage, and accept one credential per model/client instance. Spring AI 2.0.2 **ignores a per-prompt `apiKey` override** and pins an older Anthropic SDK (2.72.0) that clashed with SDK 3.1.0; adapters therefore build and cache a client per `(credentialId, version)` regardless of library. LangChain4j was uniform across Anthropic and OpenAI modules; Gemini was not exercised.
Spring AI was not rejected on quality: it ties adapters to Boot majors and adds nothing we need once we own the loop and use the MCP SDK directly.
See ADR-0012/0013 for per-user credentials and provider registries.
## Consequences
+ Library lock-in contained to one module. − Some duplication of library features.
## Revisit when
A library ships durable-execution/approval primitives that cover our needs.
