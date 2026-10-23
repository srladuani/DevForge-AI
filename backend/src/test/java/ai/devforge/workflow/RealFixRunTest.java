package ai.devforge.workflow;

import static ai.devforge.testsupport.ScriptedAnthropicServer.toolUse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.execution.ValidationReport;
import ai.devforge.execution.ValidationReport.Status;
import ai.devforge.execution.ValidationRequest;
import ai.devforge.execution.Validator;
import ai.devforge.execution.WorkCopy;
import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.FixtureRepos;
import ai.devforge.testsupport.ScriptedAnthropicServer;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real mode through the whole fix pipeline: proposal, approval, edits in a private copy, patch, validation. The model is scripted and the test
 * runner is a stub (the sandbox and real build tools are covered by their own tests), but the work copies, path rules, approval gate, patches
 * and the workflow are the production ones. It proves our side; it does not prove how Claude behaves.
 */
@TestPropertySource(properties = {"devforge.agent.mode=real", "devforge.workflow.queue-name=real-fix", "devforge.agent.max-tool-calls=6", "devforge.workflow.retry-backoff-millis=10"})
class RealFixRunTest extends WorkflowTestBase {
    private static final String ADMIN = "admin@example.test";
    private static final String BO = "src/main/java/com/acme/video/TrainingVideoBO.java";
    private static final String TEST = "src/test/java/com/acme/video/TrainingVideoBOTest.java";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Replaces the sandboxed runner: returns scripted reports and remembers what the patched copy looked like when it was asked. */
    static final class StubValidator implements Validator {
        final Deque<ValidationReport> script = new ArrayDeque<>();
        final List<String> boAsSeen = new CopyOnWriteArrayList<>();
        final List<ValidationRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public ValidationReport validate(WorkCopy copy, ValidationRequest request, Path cacheDir, BooleanSupplier cancelled) {
            requests.add(request);
            boAsSeen.add(copy.read(BO));
            ValidationReport r = script.poll();
            return r != null ? r : new ValidationReport(Status.PASSED, "./gradlew test", 4, 0, 0, 0, List.of(), "", "BUILD SUCCESSFUL");
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean @Primary StubValidator stubValidator() { return new StubValidator(); }
    }

    @Autowired StubValidator validator;
    private final ScriptedAnthropicServer server = new ScriptedAnthropicServer();
    private String providerId;

    @Override
    protected void beforeKeys() throws Exception {
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
        String list = api.get(ADMIN, "/api/v1/admin/provider-instances?kind=AI_PROVIDER").andReturn().getResponse().getContentAsString();
        providerId = JsonPath.<List<String>>read(list, "$[?(@.adapterType=='ANTHROPIC')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"" + server.url() + "\"}").andExpect(status().isOk());
        validator.script.clear();
        validator.boAsSeen.clear();
        validator.requests.clear();
    }

    @AfterEach
    void restore() throws Exception {
        if (providerId != null) api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        server.close();
    }

    private static String args(Map<String, Object> m) { return JSON.writeValueAsString(m); }

    private ScriptedAnthropicServer findings() {
        String evidence = args(Map.of("summary", "Videos are not filtered by role", "findings", List.of(Map.of("label", "VERIFIED", "text", "TrainingVideoBO.videosFor ignores its role argument", "confidence", "HIGH",
            "evidence", List.of(Map.of("repo", "api", "path", BO, "startLine", 6, "endLine", 7)))), "hypotheses", List.of(), "unknowns", List.of()));
        return server.then(toolUse("f", "submit_findings", evidence, 1000, 200));
    }

    private static String proposal(String path, boolean isNew) {
        return args(Map.of("summary", "Filter the videos by role in the BO", "recommended", "Return only the videos assigned to the role", "alternatives", List.of("Filter in the controller: spreads the rule"),
            "files", List.of(Map.of("repo", "api", "path", BO, "isNew", false, "why", "the rule lives here"), Map.of("repo", "api", "path", path, "isNew", isNew, "why", "cover it")),
            "commands", List.of("./gradlew test"), "risks", List.of("Other callers may rely on the full list"), "testPlan", List.of("add a BO test"), "outOfScope", List.of("UI changes")));
    }

    private static String edit(String path, String oldText, String newText) { return args(Map.of("repo", "api", "path", path, "old_text", oldText, "new_text", newText)); }

    private static final String OLD = "// returns every navigation video regardless of role\n        return List.of(\"how to add user\", \"audit clearance\");";
    private static final String NEW = "return \"admin\".equals(role) ? List.of(\"how to add user\", \"audit clearance\") : List.of(\"audit clearance\");";

    private String start(String mode) throws Exception {
        return Api.json(rawCreate(u("owner"), body(mode, TICKET_TEXT)).andExpect(status().isAccepted()), "$.id");
    }

    private void approve(String run) throws Exception {
        awaitStatus(run, "AWAITING_APPROVAL");
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
    }

    @Test
    void aRealFixIsProposedApprovedEditedInAPrivateCopyAndValidated() throws Exception {
        findings().then(toolUse("p", "submit_proposal", proposal(TEST, true), 2000, 400))
            .then(toolUse("e1", "edit_file", edit(BO, OLD, NEW), 3000, 100))
            .then(toolUse("e2", "create_file", args(Map.of("repo", "api", "path", TEST, "content", "package com.acme.video;\nclass TrainingVideoBOTest { }\n")), 3200, 90))
            .then(toolUse("e3", "read_changed", args(Map.of("repo", "api", "path", BO)), 3400, 30))
            .then(toolUse("e4", "finish_implementation", args(Map.of("summary", "Filtered by role and added a test", "notes", List.of())), 3600, 60));
        String run = start("FIX_AND_PATCH");
        awaitStatus(run, "AWAITING_APPROVAL");
        api.get(u("owner"), "/api/v1/runs/" + run + "/artifacts").andExpect(jsonPath("$[?(@.type=='FIX_PROPOSAL')].content.simulated").value(false));
        api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andExpect(jsonPath("$.length()").value(0));
        assertThat(server.messageRequests()).as("nothing past the proposal ran before approval").hasSize(2);

        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitStatus(run, "AWAITING_REVIEW");

        String changes = api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(changes, "$[*].repoName")).containsExactly("api");
        assertThat(JsonPath.<List<Boolean>>read(changes, "$[*].simulated")).containsExactly(false);
        String patch = JsonPath.<List<String>>read(changes, "$[*].patch").getFirst();
        assertThat(patch).contains("+++ b/" + BO).contains("+++ b/" + TEST).contains("audit clearance").doesNotContain("SIMULATED");
        assertThat(JsonPath.<List<List<String>>>read(changes, "$[*].files").getFirst()).containsExactlyInAnyOrder(BO, TEST);

        String validations = api.get(u("owner"), "/api/v1/runs/" + run + "/validations").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(validations, "$[*].status")).containsExactly("PASSED");
        assertThat(JsonPath.<List<String>>read(validations, "$[*].summary").getFirst()).contains("4 test(s)").contains("BUILD SUCCESSFUL");
        assertThat(validator.boAsSeen.getFirst()).as("validation ran on a clean copy with the stored patch applied").contains("\"admin\".equals(role)");
        assertThat(validator.requests.getFirst().tool()).isEqualTo("gradle");

        // the owner's checkout was never written to, and only their key was used
        assertThat(FixtureRepos.git(tmp.resolve("backend"), "status", "--porcelain")).isBlank();
        assertThat(server.messageRequests()).extracting(ScriptedAnthropicServer.Recorded::apiKey).containsOnly("sim-key-owner-" + suffix);
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.inputTokens").value(1000 + 2000 + 3000 + 3200 + 3400 + 3600)).andExpect(jsonPath("$.outputTokens").value(200 + 400 + 100 + 90 + 30 + 60));
        act(u("owner"), run, "accept", null).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void theAgentCanOnlyTouchFilesTheApprovedProposalNamed() throws Exception {
        findings().then(toolUse("p", "submit_proposal", proposal(TEST, true), 100, 10))
            .then(toolUse("e0", "edit_file", edit("src/main/java/com/acme/billing/InvoiceService.java", "InvoiceService { }", "InvoiceService { int x; }"), 100, 10))
            .then(toolUse("e1", "edit_file", edit(BO, OLD, NEW), 100, 10))
            .then(toolUse("e2", "edit_file", edit(".env", "DB_PASSWORD", "X"), 100, 10))
            .then(toolUse("e3", "edit_file", edit("build.gradle", "java", "kava"), 100, 10))
            .then(toolUse("e4", "finish_implementation", args(Map.of("summary", "done", "notes", List.of("InvoiceService may need the same change"))), 100, 10));
        String run = start("FIX_AND_PATCH");
        approve(run);
        awaitStatus(run, "AWAITING_REVIEW");
        String patch = JsonPath.<List<String>>read(api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andReturn().getResponse().getContentAsString(), "$[*].patch").getFirst();
        assertThat(patch).contains(BO).doesNotContain("InvoiceService").doesNotContain("DB_PASSWORD").doesNotContain("build.gradle");
        String bodies = String.join("\n", server.messageRequests().stream().map(ScriptedAnthropicServer.Recorded::body).toList());
        assertThat(bodies).contains("is not in the approved proposal").contains("REFUSED");
    }

    @Test
    void aProposalWithAnInventedPathOrASecretIsSentBackToBeFixedBeforeAnyoneSeesIt() throws Exception {
        String bad = args(Map.of("summary", "s", "recommended", "r", "files", List.of(Map.of("repo", "api", "path", "src/main/java/com/acme/Nope.java", "isNew", false), Map.of("repo", "api", "path", ".env", "isNew", false)),
            "risks", List.of(), "testPlan", List.of()));
        findings().then(toolUse("p1", "submit_proposal", bad, 100, 10)).then(toolUse("p2", "submit_proposal", proposal(TEST, true), 100, 10));
        String run = start("PROPOSE_FIX");
        awaitStatus(run, "FIX_PROPOSAL_AVAILABLE");
        String second = server.messageRequests().get(2).body();
        assertThat(second).contains("does not exist at the pinned commit");
        api.get(u("owner"), "/api/v1/runs/" + run + "/artifacts").andExpect(jsonPath("$[?(@.type=='FIX_PROPOSAL')].content.filesToChange.length()").value(2));
    }

    @Test
    void aFailedValidationSendsTheFailureBackAndTheSecondAttemptStartsFromTheOriginalCode() throws Exception {
        validator.script.add(new ValidationReport(Status.FAILED, "./gradlew test", 3, 1, 0, 0, List.of("com.acme.video.TrainingVideoBOTest.filters"), "", "AssertionError: expected [audit clearance]"));
        findings().then(toolUse("p", "submit_proposal", proposal(TEST, true), 100, 10))
            .then(toolUse("a1", "edit_file", edit(BO, OLD, "return List.of();"), 100, 10)).then(toolUse("a1f", "finish_implementation", args(Map.of("summary", "first try")), 100, 10))
            .then(toolUse("a2", "edit_file", edit(BO, OLD, NEW), 100, 10)).then(toolUse("a2f", "finish_implementation", args(Map.of("summary", "second try")), 100, 10));
        String run = start("FIX_AND_PATCH");
        approve(run);
        awaitStatus(run, "AWAITING_REVIEW");
        String validations = api.get(u("owner"), "/api/v1/runs/" + run + "/validations").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(validations, "$[*].status")).containsExactly("FAILED", "PASSED");
        assertThat(JsonPath.<List<Integer>>read(validations, "$[*].attempt")).containsExactly(1, 2);
        List<String> bodies = server.messageRequests().stream().map(ScriptedAnthropicServer.Recorded::body).toList();
        String secondImplementation = bodies.stream().filter(b -> b.contains("AssertionError: expected")).findFirst().orElseThrow();
        assertThat(bodies.stream().filter(b -> b.contains("AssertionError: expected"))).as("only the retry carries the failure").hasSize(bodies.size() - bodies.indexOf(secondImplementation));
        assertThat(secondImplementation).contains("AssertionError: expected [audit clearance]").contains("TrainingVideoBOTest.filters");
        assertThat(validator.boAsSeen.getLast()).contains("\"admin\".equals(role)").doesNotContain("List.of();");
        String latest = JsonPath.<List<String>>read(api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andReturn().getResponse().getContentAsString(), "$[?(@.attempt==2)].patch").getFirst();
        assertThat(latest).contains("audit clearance").doesNotContain("+        return List.of();");
    }

    @Test
    void anAgentThatChangesNothingFailsTheStepInsteadOfProducingAnEmptyPatch() throws Exception {
        findings().then(toolUse("p", "submit_proposal", proposal(TEST, true), 100, 10))
            .then(toolUse("f", "finish_implementation", args(Map.of("summary", "I did nothing", "notes", List.of("could not decide"))), 100, 10));
        String run = start("FIX_AND_PATCH");
        approve(run);
        awaitStatus(run, "FAILED");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.failure.code").value("AGENT_NO_CHANGES")).andExpect(jsonPath("$.failure.retryable").value(true));
        api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aNotRunValidationIsShownAsNotRunNeverAsAPass() throws Exception {
        validator.script.add(ValidationReport.notRun("No sandbox is available on this server, so no repository code was run"));
        findings().then(toolUse("p", "submit_proposal", proposal(TEST, true), 100, 10))
            .then(toolUse("e1", "edit_file", edit(BO, OLD, NEW), 100, 10)).then(toolUse("f", "finish_implementation", args(Map.of("summary", "ok")), 100, 10));
        String run = start("FIX_AND_PATCH");
        approve(run);
        awaitStatus(run, "AWAITING_REVIEW");
        String validations = api.get(u("owner"), "/api/v1/runs/" + run + "/validations").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(validations, "$[*].status")).containsExactly("NOT_RUN");
        assertThat(JsonPath.<List<String>>read(validations, "$[*].summary").getFirst()).contains("no repository code was run");
    }
}
