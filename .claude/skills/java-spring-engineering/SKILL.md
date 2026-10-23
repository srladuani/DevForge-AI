---
name: java-spring-engineering
description: Java 21 / Spring Boot conventions for DevForge backend code (module layering, validation, errors, persistence, security, testing). Use when writing or modifying backend code, or when reviewing Spring code in a target repo.
paths: "**/*.java, **/pom.xml, **/application*.yml"
---

# Java & Spring Boot Engineering

## Purpose
Keep backend code consistent, testable, and module-safe.

## Before coding
Read the module's existing code and `docs/architecture/backend-architecture.md`. Match surrounding style. For a *target* repo (whatever the user project is) follow **its** conventions and Java/Spring versions, not ours.

## Structure
- `ai.devforge.<module>`: `api` (public interfaces, DTOs, events), `internal` (everything else). Other modules may import only `api`. Spring Modulith `verify()` must pass.
- Layers inside a module: `web` (controllers) → `application` (use cases/services, transactions) → `domain` (entities, rules, state machines; no Spring web types) → `infrastructure` (JPA repos, adapters, clients).
- Ports for real seams only: `LlmClient`, `ToolExecutor`, `PullRequestProvider`, `TrackerClient`, `Sandbox`, `SecretResolver`.

## Rules
- Constructor injection; no field `@Autowired`; no static state; inject `Clock`.
- Controllers thin: validate (`@Valid`), map, delegate. DTOs are records; never expose JPA entities.
- Errors: domain exceptions → `@RestControllerAdvice` → RFC 7807 problem+json with stable `code`. No stack traces to clients.
- Transactions at application-service level; no LLM/network/sandbox calls inside a DB transaction.
- Persistence: Flyway migrations only (never `ddl-auto` beyond `validate`); explicit fetch strategy; optimistic locking (`@Version`) on workflow aggregates; JSONB for evolving payloads, columns for query fields.
- State changes go through the workflow state machine, never by setting status fields directly.
- Config via `@ConfigurationProperties` records, validated; secrets via `SecretResolver`, never literals; never log secrets, prompts containing code by default, or tokens.
- Security: deny-by-default `SecurityFilterChain`; method-level authz for workspace/repo access; path inputs resolved via the path-jail utility (reject `..`, symlink escapes).
- User credentials: stored only in the `identity` vault (AES-GCM envelope, key version), write-only API, decrypted in memory per call, never in logs/exceptions/prompts/DTOs, never cached by secret value. Runs carry `ownerUserId` and use only the owner's credential.
- Vendor SDK imports live only in adapter packages (ArchUnit-enforced); use skill `integration-adapter`.
- Concurrency: long work on bounded executors/job workers; handle cancellation (interrupt + cooperative checks); idempotent job steps.
- Logging: SLF4J structured, include `runId`, `stepId`, `workspaceId` via MDC. Metrics via Micrometer.

## Testing
- Unit: domain/state machine/policy (plain JUnit 5 + AssertJ, no Spring).
- Slice: `@WebMvcTest`, `@DataJpaTest` with Testcontainers PostgreSQL (not H2).
- Integration: `@SpringBootTest` for happy path + failure paths per use case; fake `LlmClient` with recorded responses; WireMock/fake MCP server.
- Architecture: Modulith verify + ArchUnit rules.
- A bug fix includes a test that fails without the fix.

## Done when
`./mvnw verify` passes (actually run), no new warnings, migrations apply on empty DB, OpenAPI diff reviewed if API changed.
