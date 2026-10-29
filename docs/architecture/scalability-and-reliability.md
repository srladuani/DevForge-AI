# Scalability and Reliability

## Sizing assumptions (to validate)
One organisation, ≤ 20 developers, ≤ ~10 concurrent runs, repos up to ~1 GB / 100k files, runs lasting 2-30 minutes. Dev machine: 14 cores / ~29 GB (shared).

## Mechanisms
| Concern | Design |
|---|---|
| Long-running work | HTTP returns 202; DB job queue; step checkpoints; SSE/polling |
| Restart survival | Lease+heartbeat; expired leases re-queued; steps resume from checkpoint; external writes use intent records + provider existence check |
| Retries | Call-level (LLM/MCP) exponential backoff+jitter, ≤3; step-level user "Resume"; no blind retry of non-idempotent actions |
| Cancellation | `cancel_requested` + cooperative checks between tool calls; container kill; HTTP client cancellation |
| Duplicates | `Idempotency-Key` unique per workspace; one active run per (ticket, repo) unless forced |
| Concurrency limits | Global worker pool (default 2-4), per-workspace cap, per-repo write lock (one active worktree writer per branch), sandbox slots = CPUs/2 |
| Backpressure | Queue depth metric; UI shows `QUEUED` position; reject beyond max queue with 429 |
| Partial failure | Findings persisted as produced; run can finish `FINDINGS_AVAILABLE incomplete=true` |
| Where state lives | Job/run/step/events in PostgreSQL; logs in file store (`data/logs/<run>`), referenced from DB, TTL-swept |
| Resource limits | Per-run token/tool/time/$ budgets; sandbox cpu/mem/pids/time; output caps; disk quota for worktrees + sweeper |
| Large repos | Managed clone cache, worktrees (cheap), shallow option, ignore rules, capped tool outputs |
| LLM provider failure | Circuit breaker, fallback model per tier (config), clear failure messages |
| Degraded integrations | Health shown in UI; prompt mode always available |

## Observability and SLOs (initial, aspirational)
Run start latency < 5 s after submit; SSE event lag < 2 s; ≥ 99% of runs reach a terminal or awaiting state without operator action; zero invalid citations shown as VERIFIED.

## When to add infrastructure
| Trigger | Add |
|---|---|
| > ~100 concurrent runs or multi-node workers | broker (e.g. RabbitMQ/Kafka) behind `JobQueue`; or Postgres partitioning first |
| Sandbox saturates API host | separate runner hosts (`Sandbox` remote adapter) → extract `execution` service |
| Multi-tenant SaaS / hostile repos | microVM isolation, per-tenant encryption, VPC models |
| Code search recall/latency failures | per-repo index (symbols → embeddings last) |
| Need for orchestration across many services | Kubernetes — only when ops team and fleet size justify |
