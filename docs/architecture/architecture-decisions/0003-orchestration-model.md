# ADR-0003: Orchestration model
**Status:** Proposed
## Context
Need reliable, resumable, auditable multi-step work (investigate → propose → approve → implement → validate → PR) where LLM output is untrusted.
## Options
1. **Free-form autonomous multi-agent swarm.** Flexible; hard to bound cost/permissions, test, resume, or audit.
2. **Single agent loop with tools, no workflow engine.** Simple; poor resumability and approval gating.
3. **Hybrid: deterministic workflow (explicit state machine, persisted) whose *steps* invoke a single tool-using agent loop with step-specific prompt, toolset, budget and output schema.**
4. Specialised agents per role (investigator, fixer, reviewer) from day one.
## Decision
Option 3. Steps: `GATHER_CONTEXT`, `INVESTIGATE`, `PROPOSE_FIX`, `IMPLEMENT`, `VALIDATE`, `REVIEW`, `PREPARE_PR`. The *workflow* (not the LLM) decides the next step and approval gates. Each step = one "agent profile" (prompt template + allowed tools + budgets + output JSON schema). Profiles are config, so "specialised agents" are cheap to add later. Splitting into separate agents requires evidence: measured quality gain from context isolation, or toolset/permission separation (e.g. a read-only reviewer that must not share the implementer's context).
Each step is checkpointed (messages, tool results, usage) so it can resume after restart; a step is idempotent or restarts from its last checkpoint.
## Consequences
+ Bounded, testable, resumable; clear human gates. − Less open-ended; new workflows require code/config.
## Revisit when
Eval results show a single loop degrading on long investigations (context rot) → introduce sub-investigations (child steps) with summarised hand-back.
