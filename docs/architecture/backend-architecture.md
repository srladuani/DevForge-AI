# Backend Architecture

Java 21, Spring Boot 4.1.x (4.1.2 verified on Maven Central 2026-10-22; pin at M1 start), Spring Modulith 2.1.x, MCP Java SDK 2.1.x, LangChain4j 1.22.x (adapters only), Maven wrapper, PostgreSQL 16, Flyway, Spring Modulith, Micrometer + OpenTelemetry, springdoc-openapi, Testcontainers.

## Package layout
```
backend/src/main/java/ai/devforge/
  DevForgeApplication.java
  platform/        errors (problem+json), ids, Clock, config props, web filters, observability
  identity/        api/ (UserApi, CredentialVault) internal/ (users, roles, vault: AES-GCM envelope)
  workspace/       api/ (WorkspaceApi, RepositoryRef, Policy) internal/{domain,application,web,infra}
  repository/      api/ (RepoReader, RepoSearch, WorktreeService) internal/...
  integration/     api/ (TrackerClient, PullRequestProvider, SecretResolver) internal/{jira,mcp,git-hosts/*}
  workflow/        api/ (RunService, RunEvents) internal/{domain(state machine),queue,steps,sse}
  agent/           api/ (LlmClient, ToolRegistry, AgentRunner) internal/{loop,profiles,prompts,tools,catalog,adapter/{anthropic,openai,gemini}}
  execution/       api/ (Sandbox) internal/{docker,validation}
  approval/        api/ (ApprovalService, PolicyEvaluator) internal/...
  audit/           api/ internal/ (event listeners → append-only table)
backend/src/main/resources/{db/migration, prompts/, agent-profiles/}
```
**Convention as built:** a module's *root package* holds its public API (interfaces, records, events); `internal` holds everything else and is invisible to other modules. Cross-module: call the other module's public interface or publish a domain event (Spring Modulith events with the persistent event publication registry for reliability). `ApplicationModules.verify()` runs in tests.

## Key design points
- **Run aggregate** (`workflow.internal.domain`): `Run`, `RunStateMachine` (pure Java, exhaustively unit-tested transition table from `workflow-state-machine.md`).
- **Job queue**: `job(id, run_id, step, state, lease_until, attempts, available_at, payload)`; `JobWorker` polls with `FOR UPDATE SKIP LOCKED`, heartbeats lease, bounded `ThreadPoolTaskExecutor` (virtual threads for I/O waits are fine; sandbox/LLM calls are I/O-bound), graceful shutdown re-queues.
- **Step handlers**: `StepHandler` interface per workflow step; handler returns `StepResult` → state machine decides the next transition. Handlers never write `run.status`.
- **Events**: `run_event` (monotonic `seq` per run) written transactionally with state changes; SSE endpoint streams from DB (poll interval 500 ms-1 s or LISTEN/NOTIFY wake-up), enabling multi-instance later.
- **Agent module** depends on ports only; per-provider adapters are the only code importing AI libraries/SDKs. Clients are created per credential (id+version), cached, and never hold a global key. `ModelCatalog` merges config seed + live `listModels`.
- **Config**: `devforge.*` properties records: model tiers, budgets, sandbox defaults, retention, repo storage root.
- **Errors**: `DomainException(code, httpStatus)` → problem+json with `code`, `traceId`.
- **API docs**: springdoc generates OpenAPI; snapshot-tested (`openapi.json` committed; diff must be reviewed).
- **Observability**: JSON logs with MDC (`runId`, `stepId`, `workspaceId`, `traceId`); metrics: runs by status, step duration, tokens, cost, tool calls, queue depth, lease expirations, sandbox time; traces around steps/LLM/tool calls; `/actuator/health` (db, queue, docker, integrations as non-critical), `/actuator/prometheus`.
- **Security**: Spring Security resource server; method security via `@PreAuthorize` using a `WorkspaceAccess` bean.

## Build and run
`cd backend && ./mvnw verify` · `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` with `docker compose up -d postgres`.

## As built in M1
- Modules: `platform` (open shared kernel), `identity` (users, vault, connections, dev auth, admin), `audit`, `repository`, `workspace`, `integration` (provider instances/adapter types), `agent` (ports, catalog, simulation), `approval`, `workflow`. `ApplicationModules.verify()` passes; `ModularityTest` guards it.
- Persistence is `JdbcClient` + records + Flyway (V1–V5), explicit SQL, optimistic compare-and-set on run status. JSON payloads are `text` columns for now.
- Boot 4 uses Jackson 3 (`tools.jackson.*`), Spring Security 7, Hibernate not on the classpath.
- Security: stateless, deny-by-default; authorization is decided on the REQUEST dispatch, so ASYNC/ERROR dispatches are permitted (otherwise live SSE streams fail with "response already committed" — found by a test).
- Workers: `JobWorker` polls the `job` table (lease + heartbeat), virtual threads, `setPaused` for draining; steps are idempotent (a re-run replaces its own output).
- Contract: `OpenApiConfig` marks record fields required unless `@Nullable` (JSpecify); `OpenApiContractTest` (snapshot) and `ContractConformanceTest` (real responses vs. schema) enforce it.
