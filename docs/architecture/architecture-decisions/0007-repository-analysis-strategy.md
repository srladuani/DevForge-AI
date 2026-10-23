# ADR-0007: Repository analysis and context selection
**Status:** Proposed
## Options
1. Embed whole repos in a vector index. Costly, stale, poor on exact identifiers, leaks code to an embedding provider.
2. **Agentic direct search** over a path-jailed read-only toolset: tree, glob, ripgrep/`git grep`, read-range, `git log/blame`, symbol outline (cheap parser). The model iteratively narrows.
3. Pre-built code graph (tree-sitter/JavaParser, call graph).
## Decision
Option 2 first. Tools return bounded, line-numbered output with truncation markers. A deterministic *repo profile* (languages, build tool, test commands, entry points, module list) is computed once per HEAD SHA and cached. Add outline/symbol indexing (option 3, Java via JavaParser, TS via tree-sitter) when evals show missed cross-file paths; add embeddings only if exact-search recall is demonstrably insufficient.
Context control: step token budget; summarise old tool results; keep an `Evidence` ledger (path, range, sha, content hash) that is rendered into prompts compactly and is the only source for UI citations. Citations are validated: the cited range must exist at that SHA and contain the claimed snippet hash; failures downgrade the claim to `HYPOTHESIS`.
### M0 evidence
LLM-free lexical retrieval (IDF-weighted `git grep` + filename bonus) from one-line fix summaries put a really-changed file in the top 10 for 88% of 60 past fixes in a Java/Gradle backend and 70% (15% no hit in top 50) in a React app; a real ticket's own text ranked the fixed class 5th (n=1). Optimistic (summaries written post-fix) and no LLM in the loop. Implication: search-first is viable for backends; for React add UI-label→code mapping and consider an outline/symbol index first there.
## Consequences
+ No index infra, always fresh, evidence verifiable. − More tool calls on huge repos (mitigated by profile + outline).
## Revisit when
Median investigation exceeds token budget or eval recall on multi-hop paths is < target.
