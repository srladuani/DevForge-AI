# ADR-0005: Persistence and job execution
**Status:** Proposed
## Options
- DB: **PostgreSQL** (JSONB, `SKIP LOCKED`, Flyway, Testcontainers) vs H2/SQLite (dev-only, behaves differently) vs document store (weak relational integrity for approvals/audit).
- Jobs: **DB-backed queue** (`job` table, `FOR UPDATE SKIP LOCKED`, leases + heartbeats) vs Redis/RabbitMQ/Kafka (new infra, dual-write) vs in-memory executor (lost on restart).
- Realtime: SSE vs WebSocket vs polling → SSE with polling fallback (ADR-0009).
## Decision
PostgreSQL 16 + Flyway; Spring Data JPA for aggregates, `JdbcClient` for the queue and event log. Workers run in-process on bounded pools (default 2 concurrent runs, configurable per workspace). A worker leases a job (`lease_until`, heartbeat); expired leases are re-queued, so restarts recover. Steps checkpoint to `run_step`/`run_event`. Duplicate submissions are rejected via an `idempotency_key` unique constraint; cancellation = `cancel_requested` flag polled between tool calls + sandbox kill. Retries: transient failures (LLM 429/5xx, MCP timeout) with exponential backoff + jitter, max 3; permanent failures → `FAILED` with cause.
## Consequences
+ One datastore, transactional outbox for free (event rows written in the same tx). − Queue throughput limited to PG; ample for tens of concurrent runs.
## Revisit when
>100 concurrent runs, multi-node workers needing fairer scheduling, or cross-system event fan-out → introduce a broker, keeping the same `JobQueue` port.

### Implementation note (M1)
Aggregates are persisted with `JdbcClient` and plain records rather than Spring Data JPA: explicit SQL, no mapping layer, and status changes are compare-and-set updates. The job queue, leases, event log and advisory-lock-protected event sequence are as described above and verified by tests (24 jobs / 8 competing workers claimed exactly once; recovery of a crashed worker's job without duplicating output). Revisit JPA only if the aggregates grow beyond what hand-written SQL keeps readable.
