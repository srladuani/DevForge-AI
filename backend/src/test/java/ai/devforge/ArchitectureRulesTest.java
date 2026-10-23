package ai.devforge;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ai.devforge.identity.Connections;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;

/** Rules that keep the design honest as the code grows. They run on every build. */
@AnalyzeClasses(packages = "ai.devforge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /** ADR-0004: vendor SDKs live only in adapters, so replacing a provider never touches the core. */
    @ArchTest
    static final ArchRule vendorLibrariesStayInsideTheAdapter = noClasses().that().resideOutsideOfPackage("ai.devforge.agent.internal.anthropic..")
        .should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..", "com.anthropic..").because("provider code must stay behind the LlmClient port (ADR-0004)");

    /** ADR-0012: a decrypted credential is only ever handed to the run owner's own model calls. */
    @ArchTest
    static final ArchRule onlyInvestigationCodeMayReadASecret = noClasses().that().resideOutsideOfPackages("ai.devforge.agent.internal.real..", "ai.devforge.identity..")
        .and().doNotHaveFullyQualifiedName("ai.devforge.workflow.internal.TicketFetcher")
        .should().callMethodWhere(target(declaredIn(Connections.class)).and(target(name("reveal")))).because("secrets must not be revealed outside the owner's own agent calls and ticket fetches");

    @ArchTest
    static final ArchRule mcpCodeStaysInsideTheIntegrationAdapters = noClasses().that().resideOutsideOfPackage("ai.devforge.integration.internal..")
        .should().dependOnClassesThat().resideInAnyPackage("ai.devforge.integration.internal.mcp..").because("the rest of the platform sees tickets, never the protocol");

    @ArchTest
    static final ArchRule repositoryCodeRunsOnlyThroughTheValidator = noClasses().that().resideOutsideOfPackage("ai.devforge.execution..")
        .should().dependOnClassesThat().haveFullyQualifiedName("ai.devforge.execution.Sandbox").orShould().dependOnClassesThat().haveFullyQualifiedName("ai.devforge.execution.SandboxSpec")
        .because("untrusted code is only ever run by the validator, inside the sandbox");

    @ArchTest
    static final ArchRule coreNeverDependsOnAnAdapter = noClasses().that().resideOutsideOfPackages("ai.devforge.agent.internal.anthropic..")
        .should().dependOnClassesThat().resideInAnyPackage("ai.devforge.agent.internal.anthropic..").because("adapters are discovered, never referenced");

    @ArchTest
    static final ArchRule theWorkflowNeverTouchesCredentialsOrVendorCode = noClasses().that().resideInAnyPackage("ai.devforge.workflow..")
        .should().dependOnClassesThat().resideInAnyPackage("ai.devforge.identity.internal..", "dev.langchain4j..").because("the engine sees only provider-neutral results");
}
