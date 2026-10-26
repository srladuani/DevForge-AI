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
@TestPropertySource(properties = {"devforge.agent.mode=real", "devforge.workflow.queue-name=real-fix", "devforge.agent.max-tool-calls=6", "devforge.workflow.retry-backoff-millis=10", "devforge.github.allow-file-remotes=true", "devforge.bitbucket.allow-file-remotes=true"})
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
    private final ai.devforge.testsupport.FakeGitHubServer gh = new ai.devforge.testsupport.FakeGitHubServer();
    private final ai.devforge.testsupport.FakeBitbucketServer bb = new ai.devforge.testsupport.FakeBitbucketServer();
    private String bitbucketInstanceId;
    private String providerId;
    private String githubInstanceId;

    @Override
    protected void beforeKeys() throws Exception {
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
        String list = api.get(ADMIN, "/api/v1/admin/provider-instances?kind=AI_PROVIDER").andReturn().getResponse().getContentAsString();
        providerId = JsonPath.<List<String>>read(list, "$[?(@.adapterType=='ANTHROPIC')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"" + server.url() + "\"}").andExpect(status().isOk());
        String hosts = api.get(ADMIN, "/api/v1/admin/provider-instances?kind=GIT_HOST").andReturn().getResponse().getContentAsString();
        githubInstanceId = JsonPath.<List<String>>read(hosts, "$[?(@.adapterType=='GITHUB')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + githubInstanceId, "{\"baseUrl\":\"" + gh.url() + "\"}").andExpect(status().isOk());
        bitbucketInstanceId = JsonPath.<List<String>>read(hosts, "$[?(@.adapterType=='BITBUCKET_CLOUD')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + bitbucketInstanceId, "{\"baseUrl\":\"" + bb.url() + "\"}").andExpect(status().isOk());
        validator.script.clear();
        validator.boAsSeen.clear();
        validator.requests.clear();
    }

    @AfterEach
    void restore() throws Exception {
        if (providerId != null) api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        if (githubInstanceId != null) api.patch(ADMIN, "/api/v1/admin/provider-instances/" + githubInstanceId, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        if (bitbucketInstanceId != null) api.patch(ADMIN, "/api/v1/admin/provider-instances/" + bitbucketInstanceId, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        bb.close();
        gh.close();
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

    private String runToReview(String testFileContent) throws Exception {
        findings().then(toolUse("p", "submit_proposal", proposal(TEST, true), 100, 10))
            .then(toolUse("e1", "edit_file", edit(BO, OLD, NEW), 100, 10))
            .then(toolUse("e2", "create_file", args(Map.of("repo", "api", "path", TEST, "content", testFileContent)), 100, 10))
            .then(toolUse("f", "finish_implementation", args(Map.of("summary", "done")), 100, 10));
        String run = start("FIX_AND_PATCH");
        approve(run);
        awaitStatus(run, "AWAITING_REVIEW");
        return run;
    }

    @Test
    void aFinishedChangeExportsAsAGitAmPatchThatReproducesTheEditOnAFreshCheckout() throws Exception {
        String run = runToReview("package com.acme.video;\nclass TrainingVideoBOTest { }\n");
        String view = api.get(u("viewer"), "/api/v1/runs/" + run + "/export").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(view, "$.branchName")).startsWith("devforge/" + run.substring(0, 8) + "-filter-the-videos-by-role");
        assertThat(JsonPath.<Boolean>read(view, "$.blocked")).isFalse();
        assertThat(JsonPath.<List<String>>read(view, "$.warnings")).isEmpty();
        String pr = JsonPath.read(view, "$.prDescription");
        assertThat(pr).contains("## How it was checked").contains("4 test(s)").contains("`api:" + BO + ":6-7`").contains("Out of scope").contains("approved by a person");
        String changeId = JsonPath.read(view, "$.repositories[0].changeId");
        String fileName = JsonPath.read(view, "$.repositories[0].patchFileName");

        var res = api.get(u("viewer"), "/api/v1/runs/" + run + "/changes/" + changeId + "/export.patch").andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getHeader("Content-Disposition")).contains("attachment").contains(fileName);
        Path patchFile = tmp.resolve("export.patch");
        java.nio.file.Files.writeString(patchFile, res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        FixtureRepos.git(tmp, "clone", "-q", tmp.resolve("backend").toString(), "fresh");
        FixtureRepos.git(tmp.resolve("fresh"), "-c", "user.name=Committer", "-c", "user.email=c@example.test", "am", patchFile.toString());
        assertThat(FixtureRepos.git(tmp.resolve("fresh"), "log", "-1", "--format=%ae|%s")).startsWith(u("owner") + "|").contains("Filter the videos by role in the BO");
        assertThat(java.nio.file.Files.readString(tmp.resolve("fresh").resolve(BO))).contains("\"admin\".equals(role)");
        assertThat(java.nio.file.Files.exists(tmp.resolve("fresh").resolve(TEST))).isTrue();
        assertThat(FixtureRepos.git(tmp.resolve("fresh"), "status", "--porcelain")).isBlank();
        assertThat(FixtureRepos.git(tmp.resolve("backend"), "status", "--porcelain")).as("the original checkout is untouched").isBlank();
        api.get(u("outsider"), "/api/v1/runs/" + run + "/export").andExpect(status().isNotFound());
    }

    @Test
    void aPatchWithACredentialIsNeverExported() throws Exception {
        String run = runToReview("class T { String k = \"AKIAABCDEFGHIJKLMNOP\"; String pw = \"password=hunter2hunter2\"; }\n");
        String view = api.get(u("owner"), "/api/v1/runs/" + run + "/export").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(view, "$.blocked")).isTrue();
        assertThat(JsonPath.<Boolean>read(view, "$.repositories[0].downloadable")).isFalse();
        assertThat(JsonPath.<List<String>>read(view, "$.repositories[0].secrets[*].rule")).contains("AWS access key", "credential assignment");
        assertThat(view).doesNotContain("AKIAABCDEFGHIJKLMNOP").doesNotContain("hunter2");
        api.get(u("owner"), "/api/v1/runs/" + run + "/changes/" + JsonPath.read(view, "$.repositories[0].changeId") + "/export.patch").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SECRET_IN_PATCH"));
    }

    @Test
    void nothingIsExportedBeforeTheChangeIsPrepared() throws Exception {
        findings().then(toolUse("p", "submit_proposal", proposal(TEST, true), 100, 10));
        String run = start("FIX_AND_PATCH");
        awaitStatus(run, "AWAITING_APPROVAL");
        api.get(u("owner"), "/api/v1/runs/" + run + "/export").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_READY_TO_EXPORT"));
    }

    // ---- publishing to GitHub (fake API + a local bare repository as the remote)

    private Path remote;

    private int pendingApprovals(String run) throws Exception {
        return JsonPath.<List<Object>>read(api.get(u("approver"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + run + "&status=PENDING").andReturn().getResponse().getContentAsString(), "$").size();
    }

    private void githubToken(String who, String token) throws Exception {
        String id = Api.json(api.post(u(who), "/api/v1/me/connections", "{\"kind\":\"GIT_HOST\",\"providerType\":\"GITHUB\",\"authMethod\":\"PAT\",\"label\":\"gh\",\"secret\":\"" + token + "\"}").andExpect(status().isCreated()), "$.id");
        api.post(u(who), "/api/v1/me/connections/" + id + "/verify", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"));
    }

    private void setUpRemote() {
        remote = tmp.resolve("acme").resolve("service.git");
        FixtureRepos.git(tmp, "init", "-q", "--bare", remote.toString());
        FixtureRepos.git(tmp.resolve("backend"), "remote", "add", "origin", remote.toUri().toString().replaceAll("/$", ""));
    }

    private String publishReady() throws Exception {
        setUpRemote();
        githubToken("owner", gh.acceptedToken);
        return runToReview("package com.acme.video;\nclass TrainingVideoBOTest { }\n");
    }

    private void awaitPublication(String run, String wanted) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() ->
            Api.json(api.get(u("owner"), "/api/v1/runs/" + run + "/publications"), "$[0].status").equals(wanted));
    }

    @Test
    void nothingLeavesUntilAPersonApprovesExactlyThePlanThenTheBranchIsPushedAndADraftPullRequestOpened() throws Exception {
        String run = publishReady();
        String requested = api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String branch = JsonPath.read(requested, "$.branch");
        assertThat(gh.pullCreations()).isEmpty();
        assertThat(FixtureRepos.git(remote, "branch", "--list")).as("nothing pushed before approval").isBlank();
        api.get(u("owner"), "/api/v1/runs/" + run + "/publications").andExpect(jsonPath("$.length()").value(0));

        String approval = pendingApprovalId(run);
        api.get(u("approver"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + run).andExpect(jsonPath("$[0].kind").value("PR")).andExpect(jsonPath("$[0].summary").value(org.hamcrest.Matchers.containsString("draft pull request")));
        decide(u("approver"), approval, "APPROVE", "sha256:not-the-plan").andExpect(status().isConflict());
        assertThat(gh.pullCreations()).isEmpty();
        decide(u("approver"), approval, "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitPublication(run, "PR_OPEN");

        api.get(u("viewer"), "/api/v1/runs/" + run + "/publications").andExpect(jsonPath("$[0].prNumber").value(7)).andExpect(jsonPath("$[0].prUrl").value("https://github.test/acme/service/pull/7"))
            .andExpect(jsonPath("$[0].branch").value(branch)).andExpect(jsonPath("$[0].baseBranch").value("main")).andExpect(jsonPath("$[0].remote").value("acme/service"));
        assertThat(FixtureRepos.git(remote, "log", "-1", "--format=%an|%ae|%s", "refs/heads/" + branch).trim()).contains(u("owner")).contains("Filter the videos by role in the BO");
        assertThat(FixtureRepos.git(remote, "rev-parse", "refs/heads/" + branch + "^").trim()).as("the commit sits on the real base commit").isEqualTo(FixtureRepos.git(tmp.resolve("backend"), "rev-parse", "HEAD").trim());
        assertThat(FixtureRepos.git(remote, "show", "refs/heads/" + branch + ":" + BO)).contains("\"admin\".equals(role)");
        var pr = gh.pullCreations().getFirst();
        assertThat(pr.body()).contains("\"draft\":true").contains("\"base\":\"main\"").contains("\"head\":\"" + branch + "\"").contains("How it was checked");
        assertThat(pr.authorization()).isEqualTo("Bearer " + gh.acceptedToken);
        String events = api.get(u("owner"), "/api/v1/runs/" + run + "/events").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(events, "$[*].type")).contains("PUBLISH_REQUESTED", "APPROVAL_DECIDED", "BRANCH_PUSHED", "PULL_REQUEST_OPENED");
        assertThat(events).doesNotContain(gh.acceptedToken);
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish/retry", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTHING_TO_RETRY"));
    }

    @Test
    void aRejectedPlanPublishesNothingAndOnlyTheOwnerCanAsk() throws Exception {
        String run = publishReady();
        api.post(u("approver"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_ONLY"));
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isAccepted());
        decide(u("approver"), pendingApprovalId(run), "REJECT", pendingApprovalHash(run)).andExpect(status().isOk());
        Thread.sleep(300);
        assertThat(gh.pullCreations()).isEmpty();
        assertThat(FixtureRepos.git(remote, "branch", "--list")).isBlank();
        api.get(u("owner"), "/api/v1/runs/" + run + "/publications").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aRefusedTokenFailsThePublicationClearlyAndTheFixedOneRetriesWithoutDuplicatingAnything() throws Exception {
        String run = publishReady();
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isAccepted());
        gh.pullCreateStatus = 403;
        gh.pullCreateMessage = "Resource not accessible by personal access token";
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitPublication(run, "FAILED");
        api.get(u("owner"), "/api/v1/runs/" + run + "/publications").andExpect(jsonPath("$[0].failureCode").value("HOST_AUTH"));
        String branch = Api.json(api.get(u("owner"), "/api/v1/runs/" + run + "/publications"), "$[0].branch");
        assertThat(FixtureRepos.git(remote, "branch", "--list").trim()).as("the branch was pushed before the PR step failed").contains(branch);

        gh.pullCreateStatus = 201;
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish/retry", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("PR_OPEN"));
        assertThat(gh.requests().stream().filter(r -> r.method().equals("POST") && r.path().endsWith("/pulls")).count()).as("one rejected attempt and one that created the PR").isEqualTo(2);
        assertThat(FixtureRepos.git(remote, "branch", "--list").lines().filter(l -> !l.isBlank()).count()).isEqualTo(1);
    }

    @Test
    void aRemoteThatIsNotTheConfiguredHostNeverReceivesTheTokenAndWithoutAConnectionTheAskIsRefused() throws Exception {
        setUpRemote();
        String run = runToReview("package com.acme.video;\nclass TrainingVideoBOTest { }\n");
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_USABLE_CONNECTION"));
        githubToken("owner", gh.acceptedToken);
        FixtureRepos.git(tmp.resolve("backend"), "remote", "set-url", "origin", "https://evil.example/acme/service.git");
        int before = gh.requests().size();
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HOST_UNSUPPORTED_REMOTE"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("never sent anywhere else")));
        assertThat(gh.requests().size()).as("no call carried the token anywhere").isEqualTo(before);
        assertThat(pendingApprovals(run)).isZero();
    }

    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void aPublicationInterruptedByARestartIsResumedWithoutCreatingAnythingTwice() throws Exception {
        String run = publishReady();
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isAccepted());
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitPublication(run, "PR_OPEN");
        String branch = Api.json(api.get(u("owner"), "/api/v1/runs/" + run + "/publications"), "$[0].branch");
        // what a restart in the middle would have left: unfinished, idle for a while
        jdbc.sql("update publication set status = 'PENDING', pr_number = null, pr_url = null, updated_at = now() - interval '10 minutes' where run_id = ?").param(java.util.UUID.fromString(run)).update();

        Class<?> publisher = Class.forName("ai.devforge.workflow.internal.Publisher");
        var m = publisher.getDeclaredMethod("recoverStalled");
        m.setAccessible(true);
        m.invoke(context.getBean(publisher));
        awaitPublication(run, "PR_OPEN");
        assertThat(gh.pullCreations()).as("the pull request that already existed was reused").hasSize(1);
        assertThat(FixtureRepos.git(remote, "branch", "--list").lines().filter(l -> !l.isBlank()).count()).isEqualTo(1);
        assertThat(FixtureRepos.git(remote, "branch", "--list")).contains(branch);
    }

    // ---- publishing to Bitbucket Cloud

    private void bitbucketCredential(String who, String secret) throws Exception {
        String id = Api.json(api.post(u(who), "/api/v1/me/connections", "{\"kind\":\"GIT_HOST\",\"providerType\":\"BITBUCKET_CLOUD\",\"authMethod\":\"PAT\",\"label\":\"bb\",\"secret\":\"" + secret + "\"}").andExpect(status().isCreated()), "$.id");
        api.post(u(who), "/api/v1/me/connections/" + id + "/verify", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"));
    }

    private void setUpBitbucketRemote() {
        remote = tmp.resolve("bitbucket").resolve("acme").resolve("service.git");
        FixtureRepos.git(tmp, "init", "-q", "--bare", remote.toString());
        FixtureRepos.git(tmp.resolve("backend"), "remote", "add", "origin", remote.toUri().toString().replaceAll("/$", ""));
    }

    private String bitbucketReady(String secret) throws Exception {
        setUpBitbucketRemote();
        bitbucketCredential("owner", secret);
        return runToReview("package com.acme.video;\nclass TrainingVideoBOTest { }\n");
    }

    private String publishAndApprove(String run) throws Exception {
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isAccepted());
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitPublication(run, "PR_OPEN");
        return Api.json(api.get(u("owner"), "/api/v1/runs/" + run + "/publications"), "$[0].branch");
    }

    @Test
    void aProjectPublishesToBitbucketIntoItsConfiguredTargetBranchWithItsOwnBranchPrefixAndPullRequestKind() throws Exception {
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/publishing", "{\"targetBranch\":\"develop\",\"branchPrefix\":\"feature/\",\"draft\":false}").andExpect(status().isOk());
        String run = bitbucketReady(bb.acceptedBearer);
        String branch = publishAndApprove(run);
        assertThat(branch).startsWith("feature/" + run.substring(0, 8) + "-filter-the-videos");
        var pr = bb.pullCreations().getFirst();
        assertThat(pr.body()).contains("\"destination\":{\"branch\":{\"name\":\"develop\"}}").contains("\"name\":\"" + branch + "\"").contains("\"draft\":false").contains("How it was checked");
        assertThat(pr.authorization()).isEqualTo("Bearer " + bb.acceptedBearer);
        api.get(u("viewer"), "/api/v1/runs/" + run + "/publications").andExpect(jsonPath("$[0].prUrl").value(org.hamcrest.Matchers.containsString("pull-requests/11"))).andExpect(jsonPath("$[0].baseBranch").value("develop"));
        assertThat(FixtureRepos.git(remote, "branch", "--list")).contains(branch);
        assertThat(gh.requests()).as("a Bitbucket repository never touches GitHub").isEmpty();
        api.get(u("approver"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + run).andExpect(jsonPath("$[0].summary").value(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("develop"), org.hamcrest.Matchers.containsString("BITBUCKET_CLOUD"))));
    }

    @Test
    void withoutATargetBranchTheRepositorysMainBranchIsUsedAndAnAppPasswordGoesAsBasic() throws Exception {
        bb.mainBranch = "trunk";
        String run = bitbucketReady(bb.acceptedBasic);
        publishAndApprove(run);
        var pr = bb.pullCreations().getFirst();
        assertThat(pr.body()).contains("\"destination\":{\"branch\":{\"name\":\"trunk\"}}").contains("\"draft\":true");
        assertThat(pr.authorization()).startsWith("Basic ");
    }

    @Test
    void aRejectedPullRequestShowsBitbucketsReasonAndTheTokenIsNeverInEvents() throws Exception {
        String run = bitbucketReady(bb.acceptedBearer);
        bb.pullCreateStatus = 400;
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish", null).andExpect(status().isAccepted());
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitPublication(run, "FAILED");
        api.get(u("owner"), "/api/v1/runs/" + run + "/publications").andExpect(jsonPath("$[0].failureCode").value("HOST_REJECTED")).andExpect(jsonPath("$[0].failureMessage").value(org.hamcrest.Matchers.containsString("Pull request could not be created")));
        String events = api.get(u("owner"), "/api/v1/runs/" + run + "/events").andReturn().getResponse().getContentAsString();
        assertThat(events).doesNotContain(bb.acceptedBearer);
        bb.pullCreateStatus = 200;
        api.post(u("owner"), "/api/v1/runs/" + run + "/publish/retry", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("PR_OPEN"));
    }

    @Test
    void publishSettingsAreValidatedHaveDefaultsAndOnlyAdminsChangeThem() throws Exception {
        api.get(u("viewer"), "/api/v1/workspaces/" + ws + "/publishing").andExpect(status().isOk()).andExpect(jsonPath("$.branchPrefix").value("devforge/")).andExpect(jsonPath("$.draft").value(true)).andExpect(jsonPath("$.targetBranch").doesNotExist());
        api.put(u("operator"), "/api/v1/workspaces/" + ws + "/publishing", "{\"targetBranch\":\"develop\",\"branchPrefix\":\"x/\",\"draft\":true}").andExpect(status().isForbidden());
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/publishing", "{\"targetBranch\":\"../etc\",\"branchPrefix\":\"x/\",\"draft\":true}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PUBLISH_SETTINGS"));
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/publishing", "{\"targetBranch\":\"develop\",\"branchPrefix\":\"-bad\",\"draft\":true}").andExpect(status().isBadRequest());
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/publishing", "{\"targetBranch\":\"\",\"branchPrefix\":\"feature/\",\"draft\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.targetBranch").doesNotExist());
        api.get(u("outsider"), "/api/v1/workspaces/" + ws + "/publishing").andExpect(status().isNotFound());
    }
}
