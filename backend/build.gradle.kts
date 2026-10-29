import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "ai.devforge"
version = "0.0.1-SNAPSHOT"
description = "DevForge AI backend (modular monolith)"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

val springModulithVersion = "2.1.1"
val springdocVersion = "3.1.1"
val archunitVersion = "1.5.1"
val langchain4jVersion = "1.22.0"
val jackson2Version = "2.22.3"

dependencies {
    // Versions of Spring Boot's own dependencies come from its BOM; Spring Modulith has its own.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:$springModulithVersion"))
    // LangChain4j still uses Jackson 2 (Spring Boot itself uses Jackson 3). Raised past the versions with known vulnerabilities found by scripts/audit-deps.sh;
    // the highest version among the platforms wins, so this is safe to leave in place until Spring Boot's own BOM catches up.
    implementation(platform("com.fasterxml.jackson:jackson-bom:$jackson2Version"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-micrometer-metrics")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocVersion")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("dev.langchain4j:langchain4j-anthropic:$langchain4jVersion")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-micrometer-metrics-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    // Settings that tests read from the command line or the environment: `./gradlew test -Dopenapi.update=true`; secrets such as
    // ANTHROPIC_API_KEY and DEVFORGE_SMOKE_NODE_HOME are inherited from the environment as usual.
    listOf("openapi.update").forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // `scripts/dev.sh` runs the backend with the "local" profile; set SPRING_PROFILES_ACTIVE yourself for anything else.
    systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "local")
}

// Lists the resolved runtime libraries as "DEP group:name:version" for scripts/audit-deps.sh (vulnerability check).
tasks.register("printRuntimeDeps") {
    notCompatibleWithConfigurationCache("reads resolved configurations at execution time")
    doLast {
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }.distinct().sortedBy { "${it.group}:${it.name}" }
            .forEach { println("DEP ${it.group}:${it.name}:${it.version}") }
    }
}
