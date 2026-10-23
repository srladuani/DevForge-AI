# M0 spike code (throwaway)

Evidence for `docs/engineering/spikes/m0-results.md`. Not product code; do not import from it.
- `s1-ai-libs/`: Spring AI vs LangChain4j vs Anthropic SDK against local fake LLM servers (needs a Maven wrapper: generate with start.spring.io, Boot 4.1.x, copy `pom.xml` + tests in).
- `s3-sandbox/`: bubblewrap stand-in for the Docker sandbox + Gradle "resolve everything" init script.
- `s4-search-recall/recall.py`: LLM-free retrieval recall over past fix commits (`python3 -I recall.py <repo> <src-prefix> <exts> [max]`).
