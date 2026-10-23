# ADR-0002: Maven for the backend
**Status: Superseded by [ADR-0015](0015-gradle-build.md) (Gradle).** Kept for the record.
**Status:** Proposed
## Context
Java 21 installed locally; neither Maven nor Gradle is installed, so a wrapper is required either way. Target repos' build tool unknown (open question).
## Options
- **Maven:** declarative, uniform, strong Spring Boot/Modulith/Testcontainers support, easy for the wider Java team to read, predictable.
- **Gradle:** faster incremental builds, flexible; Kotlin/Groovy DSL adds scripting complexity and variance.
## Decision
Maven with `./mvnw`. Single `backend/pom.xml` (no multi-module until needed).
## Consequences
+ Low learning curve, reproducible. − Slower large builds; fine at this size. Platform must still *detect* Maven/Gradle/npm in target repos (ADR-0007).
## Revisit when
Build time >10 min or the team standardises on Gradle.
