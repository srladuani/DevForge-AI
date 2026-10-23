# ADR-0015: Gradle (Kotlin DSL) for the backend build
**Status:** Accepted (supersedes ADR-0002)
## Context
ADR-0002 chose Maven. The team's own projects (and the first test project) build with Gradle, so the people working on DevForge know it and can run the same toolchain they use elsewhere. Nothing in the product depended on Maven: the build is one module, plugins are standard, and tests are plain JUnit 5.
## Decision
Gradle with the Kotlin DSL and the Gradle wrapper (`./gradlew`), pinned to a version and a distribution checksum in `backend/gradle/wrapper/gradle-wrapper.properties`. Spring Boot's Gradle plugin supplies `bootRun`/`bootJar` and the dependency BOM; Spring Modulith's BOM is imported as a platform. Java 21 through a toolchain. A single `backend/build.gradle.kts` (no multi-project until needed).
- Run: `./gradlew bootRun` (activates the `local` profile unless `SPRING_PROFILES_ACTIVE` is set). Verify: `./gradlew clean build`. One test class: `./gradlew test --tests OpenApiContractTest`.
- Command-line settings that tests read (`-Dopenapi.update=true`) are forwarded to the test JVM in `tasks.test`; environment variables (`ANTHROPIC_API_KEY`, `DEVFORGE_SMOKE_NODE_HOME`) are inherited as usual.
- Test results are JUnit XML in `backend/build/test-results/test/`; other outputs (eval reports) go under `backend/build/`.
## Consequences
+ Same tool the team uses for its own projects; incremental builds and a build cache. − Dependencies are resolved from Maven Central into the Gradle cache (`~/.gradle`), not `~/.m2`; first build is slow. − The product still *detects* Maven, Gradle and npm in target repositories (ADR-0007/0013); this decision is only about how DevForge itself is built.
