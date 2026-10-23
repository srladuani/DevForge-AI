package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.testsupport.Api;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunLifecycleTest extends WorkflowTestBase {

    @Test
    void investigateOnlyRunProducesVerifiedEvidenceAcrossBothRepositoriesAndStopsForAHuman() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");

        assertThat(statesVisited(run)).containsExactly("INPUT_RETRIEVAL", "CONTEXT_GATHERING", "INVESTIGATION", "FINDINGS_AVAILABLE");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.agent.simulated").value(true)).andExpect(jsonPath("$.agent.model").value("claude-sonnet-5-5"))
            .andExpect(jsonPath("$.repositories.length()").value(2)).andExpect(jsonPath("$.nextActions").value(org.hamcrest.Matchers.hasItems("request-fix", "complete", "cancel")));

        String findings = api.get(u("owner"), "/api/v1/runs/" + run + "/findings").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(findings, "$[*].text")).allMatch(t -> t.startsWith("[SIMULATED]"));
        // every piece of cited evidence was re-read from the pinned commit
        assertThat(JsonPath.<List<Boolean>>read(findings, "$[*].evidence[*].verified")).isNotEmpty().allMatch(v -> v);
        assertThat(JsonPath.<List<String>>read(findings, "$[*].evidence[*].excerpt")).anyMatch(e -> e.toLowerCase().contains("video"));
        assertThat(JsonPath.<List<String>>read(findings, "$[*].label")).contains("VERIFIED");
        // backend and frontend were both examined, and the API call in the UI was linked to the backend mapping
        assertThat(JsonPath.<List<String>>read(findings, "$[*].evidence[*].repoName")).contains("api", "admin-portal");
        assertThat(JsonPath.<List<String>>read(findings, "$[*].text")).anyMatch(t -> t.contains("Cross-repo link") && t.contains("/api/v1/training-videos"));

        api.get(u("owner"), "/api/v1/runs/" + run + "/artifacts").andExpect(jsonPath("$[?(@.type=='ROOT_CAUSE')].content.simulated").value(true));
        api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andExpect(jsonPath("$.length()").value(0));
        assertThat(eventTypes(run)).contains("RUN_CREATED", "INPUT_NORMALIZED", "REPO_CONTEXT", "TOOL_CALL", "FINDING_ADDED", "ARTIFACT_CREATED", "USAGE");
        api.get(u("owner"), "/api/v1/runs/" + run + "/steps").andExpect(jsonPath("$[?(@.name=='INVESTIGATION')].status").value("SUCCEEDED"));

        act(u("owner"), run, "complete", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.nextActions.length()").value(0));
    }

    @Test
    void fullFixPathNeedsAHumanApprovalAndEndsWithPerRepositoryPatchesThatWereNeverReportedAsPassing() throws Exception {
        String run = createRun("FIX_AND_PATCH", TICKET_TEXT);
        awaitStatus(run, "AWAITING_APPROVAL");
        assertThat(statesVisited(run)).containsExactly("INPUT_RETRIEVAL", "CONTEXT_GATHERING", "INVESTIGATION", "FINDINGS_AVAILABLE", "PROPOSING_FIX", "FIX_PROPOSAL_AVAILABLE", "AWAITING_APPROVAL");
        api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andExpect(jsonPath("$.length()").value(0));

        String approval = pendingApprovalId(run);
        String hash = pendingApprovalHash(run);
        api.get(u("approver"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + run).andExpect(jsonPath("$[0].summary").value(org.hamcrest.Matchers.containsString("filesToChange")));
        decide(u("approver"), approval, "APPROVE", "sha256:not-what-i-reviewed").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARTIFACT_CHANGED"));
        assertThat(statusOf(run)).as("a wrong-hash decision changes nothing").isEqualTo("AWAITING_APPROVAL");

        decide(u("approver"), approval, "APPROVE", hash).andExpect(status().isOk());
        awaitStatus(run, "AWAITING_REVIEW");

        String changes = api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(changes, "$[*].repoName")).containsExactlyInAnyOrder("api", "admin-portal");
        assertThat(JsonPath.<List<String>>read(changes, "$[*].patch")).allMatch(p -> p.contains("SIMULATED change") && p.startsWith("--- a/"));
        assertThat(JsonPath.<List<Boolean>>read(changes, "$[*].simulated")).allMatch(s -> s);
        String validations = api.get(u("owner"), "/api/v1/runs/" + run + "/validations").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(validations, "$[*].status")).isNotEmpty().allMatch(s -> s.equals("SIMULATED")).doesNotContain("PASSED");
        assertThat(JsonPath.<List<String>>read(validations, "$[*].summary")).allMatch(s -> s.contains("NOT executed") || s.contains("No test command"));

        act(u("owner"), run, "accept", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(statesVisited(run)).endsWith("IMPLEMENTATION", "VALIDATION", "AWAITING_REVIEW", "COMPLETED");
        assertThat(eventTypes(run)).contains("APPROVAL_REQUESTED", "APPROVAL_DECIDED", "CHANGE_PREPARED", "VALIDATION_RESULT");
    }

    @Test
    void aRejectedApprovalSendsTheRunBackWithoutEverEditingAnything() throws Exception {
        String run = createRun("FIX_AND_PATCH", TICKET_TEXT);
        awaitStatus(run, "AWAITING_APPROVAL");
        decide(u("approver"), pendingApprovalId(run), "REJECT", pendingApprovalHash(run)).andExpect(status().isOk());
        assertThat(statusOf(run)).isEqualTo("FIX_PROPOSAL_AVAILABLE");
        Thread.sleep(400); // give a (wrongly) running worker a chance to show itself
        assertThat(statusOf(run)).isEqualTo("FIX_PROPOSAL_AVAILABLE");
        api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andExpect(jsonPath("$.length()").value(0));
        assertThat(statesVisited(run)).doesNotContain("IMPLEMENTATION");
        // the owner can ask again, which opens a fresh approval
        act(u("owner"), run, "request-implementation", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitStatus(run, "AWAITING_REVIEW");
    }

    @Test
    void proposeFixModeStopsAtTheProposalUntilSomeoneAsksForImplementation() throws Exception {
        String run = createRun("PROPOSE_FIX", TICKET_TEXT);
        awaitStatus(run, "FIX_PROPOSAL_AVAILABLE");
        api.get(u("owner"), "/api/v1/runs/" + run + "/artifacts").andExpect(jsonPath("$[?(@.type=='FIX_PROPOSAL')].content.filesToChange.length()").value(2));
        assertThat(pendingApprovals(run)).isZero();
        act(u("owner"), run, "complete", null).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void anInvestigateOnlyRunCanBeUpgradedToAFixLater() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        act(u("owner"), run, "request-fix", "{\"mode\":\"PROPOSE_FIX\"}").andExpect(status().isOk());
        awaitStatus(run, "FIX_PROPOSAL_AVAILABLE");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.mode").value("PROPOSE_FIX"));
    }

    @Test
    void reviewerFeedbackLoopsBackToImplementationAndEachAttemptIsKept() throws Exception {
        String run = createRun("FIX_AND_PATCH", TICKET_TEXT);
        awaitStatus(run, "AWAITING_APPROVAL");
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitStatus(run, "AWAITING_REVIEW");
        act(u("owner"), run, "request-changes", "{\"text\":\"Please also cover the null role case\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IMPLEMENTATION"));
        awaitStatus(run, "AWAITING_REVIEW");
        String changes = api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(changes, "$[*].attempt")).contains(1, 2);
        act(u("owner"), run, "request-changes", "{\"text\":\"\"}").andExpect(status().isBadRequest());
    }

    @Test
    void runsAreReproducibleBecauseEachRepositoryIsPinnedToTheCommitItHadAtStart() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        String sha = Api.json(api.get(u("owner"), "/api/v1/runs/" + run), "$.repositories[?(@.name=='api')].baseSha");
        assertThat(sha).matches("\\[?\"?[0-9a-f]{40}\"?]?");
        String findings = api.get(u("owner"), "/api/v1/runs/" + run + "/findings").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(findings, "$[?(@.evidence[0].repoName=='api')].evidence[0].sha")).allMatch(s -> sha.contains(s));
    }

    private int pendingApprovals(String run) throws Exception {
        return JsonPath.<List<?>>read(api.get(u("approver"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + run + "&status=PENDING").andReturn().getResponse().getContentAsString(), "$").size();
    }
}
