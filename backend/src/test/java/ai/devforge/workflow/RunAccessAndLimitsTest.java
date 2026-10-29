package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.testsupport.Api;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class RunAccessAndLimitsTest extends WorkflowTestBase {

    // ---- who may do what
    @Test
    void onlyMembersWithTheRunPermissionCanStartRuns() throws Exception {
        rawCreate(u("viewer"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
        rawCreate(u("outsider"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
        rawCreate(u("operator"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isAccepted());
    }

    @Test
    void outsidersCannotSeeAnythingAboutARunAndViewersSeeButCannotAct() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        for (String path : new String[] {"", "/steps", "/findings", "/artifacts", "/changes", "/validations", "/events"}) {
            api.get(u("outsider"), "/api/v1/runs/" + run + path).andExpect(status().isNotFound());
            api.get(u("viewer"), "/api/v1/runs/" + run + path).andExpect(status().isOk());
        }
        api.get(u("outsider"), "/api/v1/workspaces/" + ws + "/runs").andExpect(status().isNotFound());
        api.get(u("viewer"), "/api/v1/runs/" + run).andExpect(jsonPath("$.nextActions.length()").value(0));
        act(u("viewer"), run, "complete", null).andExpect(status().isForbidden());
        act(u("outsider"), run, "complete", null).andExpect(status().isNotFound());
        api.get(u("owner"), "/api/v1/runs/" + UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    void aRunAlwaysUsesItsOwnersKeyNeverAnotherMembers() throws Exception {
        String ownersRun = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        String operatorsRun = Api.json(rawCreate(u("operator"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isAccepted()), "$.id");
        for (String run : new String[] {ownersRun, operatorsRun}) {
            String conn = jdbc.sql("select connection_id from run where id = ?::uuid").param(run).query(String.class).single();
            String connOwner = jdbc.sql("select user_id from connection where id = ?::uuid").param(conn).query(String.class).single();
            assertThat(connOwner).isEqualTo(jdbc.sql("select owner_user_id from run where id = ?::uuid").param(run).query(String.class).single());
        }
        // a member with no key of their own cannot borrow anyone else's
        api.get(u("approver"), "/api/v1/workspaces").andExpect(status().isOk());
        rawCreate(u("approver"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_USABLE_CONNECTION"));
    }

    @Test
    void theProjectPolicyCanForbidAProviderForNewRuns() throws Exception {
        api.patch(u("owner"), "/api/v1/workspaces/" + ws, "{\"policy\":{\"allowedProviders\":[\"GEMINI\"],\"requireDifferentApprover\":false,\"maxRunCostUsd\":2,\"maxConcurrentRuns\":2}}").andExpect(status().isOk());
        rawCreate(u("owner"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PROVIDER_NOT_ALLOWED"));
    }

    // ---- input validation
    @Test
    void inputsAreValidatedBeforeAnythingIsCreated() throws Exception {
        int before = runCount();
        rawCreate(u("owner"), "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"PROMPT\",\"text\":\"   \"},\"repositoryIds\":[\"" + backendId + "\"],\"agent\":" + agent() + "}").andExpect(status().isBadRequest());
        rawCreate(u("owner"), "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"PROMPT\",\"text\":\"" + "x".repeat(20_001) + "\"},\"repositoryIds\":[\"" + backendId + "\"],\"agent\":" + agent() + "}").andExpect(status().isBadRequest());
        rawCreate(u("owner"), "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"PROMPT\",\"text\":\"a real description of a problem\"},\"repositoryIds\":[],\"agent\":" + agent() + "}").andExpect(status().isBadRequest());
        rawCreate(u("owner"), "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"PROMPT\",\"text\":\"a real description of a problem\"},\"repositoryIds\":[\"" + UUID.randomUUID() + "\"],\"agent\":" + agent() + "}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REPOSITORY_NOT_IN_PROJECT"));
        rawCreate(u("owner"), "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"EMAIL\",\"text\":\"a real description of a problem\"},\"repositoryIds\":[\"" + backendId + "\"],\"agent\":" + agent() + "}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_INPUT_KIND"));
        rawCreate(u("owner"), "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"TICKET\",\"ref\":\"not a key\",\"text\":\"a real description of a problem\"},\"repositoryIds\":[\"" + backendId + "\"],\"agent\":" + agent() + "}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TICKET_KEY"));
        rawCreate(u("owner"), "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"PROMPT\",\"text\":\"a real description of a problem\"},\"repositoryIds\":[\"" + backendId + "\"],\"agent\":{\"provider\":\"ANTHROPIC\",\"model\":\"claude-nope\"}}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MODEL_NOT_AVAILABLE"));
        assertThat(runCount()).as("nothing was created by any rejected request").isEqualTo(before);
    }

    @Test
    void aTicketInputKeepsItsKeyAndTheTrackerIsHonestlyReportedAsNotFetched() throws Exception {
        String body = "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"TICKET\",\"ref\":\"PBPR-8500\",\"text\":" + quote(TICKET_TEXT) + "},\"repositoryIds\":[\"" + backendId + "\"],\"agent\":" + agent() + "}";
        String run = Api.json(rawCreate(u("owner"), body).andExpect(status().isAccepted()).andExpect(jsonPath("$.title").value("PBPR-8500")), "$.id");
        awaitStatus(run, "FINDINGS_AVAILABLE");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.inputs[0].normalized.source.fetchedFromTracker").value(false)).andExpect(jsonPath("$.inputs[0].sourceRef").value("PBPR-8500"));
    }

    // ---- idempotency and limits
    @Test
    void submittingTheSameRequestTwiceWithTheSameKeyReturnsTheSameRun() throws Exception {
        String key = "key-" + UUID.randomUUID();
        String b = body("INVESTIGATE_ONLY", TICKET_TEXT);
        String first = Api.json(post(key, b).andExpect(status().isAccepted()), "$.id");
        post(key, b).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true")).andExpect(jsonPath("$.id").value(first));
        post(key, body("INVESTIGATE_ONLY", TICKET_TEXT + " (changed)")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        post("short", b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        assertThat(jdbc.sql("select count(*) from run where idempotency_key = ?").param(key).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void manyConcurrentDuplicateSubmissionsCreateExactlyOneRun() throws Exception {
        String key = "dup-" + UUID.randomUUID();
        String b = body("INVESTIGATE_ONLY", TICKET_TEXT);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<String> c = () -> {
                go.await();
                var r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + ws + "/runs").header("X-Devforge-User", u("owner")).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(b)).andReturn().getResponse();
                return r.getStatus() + ":" + (r.getStatus() < 300 ? com.jayway.jsonpath.JsonPath.<String>read(r.getContentAsString(), "$.id") : r.getContentAsString());
            };
            results.add(pool.submit(c));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) outcomes.add(f.get());
        pool.shutdown();
        assertThat(outcomes).allMatch(o -> o.startsWith("202:") || o.startsWith("200:"));
        assertThat(outcomes.stream().map(o -> o.substring(4)).distinct()).as("everyone got the same run").hasSize(1);
        assertThat(jdbc.sql("select count(*) from run where idempotency_key = ?").param(key).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aProjectCannotHaveMoreRunsExecutingThanItsPolicyAllows() throws Exception {
        api.patch(u("owner"), "/api/v1/workspaces/" + ws, "{\"policy\":{\"allowedProviders\":[],\"requireDifferentApprover\":false,\"maxRunCostUsd\":2,\"maxConcurrentRuns\":1}}").andExpect(status().isOk());
        String slow = createRun("INVESTIGATE_ONLY", TICKET_TEXT + "\n[sim:slow=2500]");
        rawCreate(u("owner"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ACTIVE_RUNS"));
        awaitStatus(slow, "FINDINGS_AVAILABLE");
        rawCreate(u("owner"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(status().isAccepted()); // resting runs do not count as executing
    }

    @Test
    void runListsAreFilterableByStatus() throws Exception {
        String done = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(done, "FINDINGS_AVAILABLE");
        act(u("owner"), done, "complete", null).andExpect(status().isOk());
        String resting = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(resting, "FINDINGS_AVAILABLE");
        api.get(u("viewer"), "/api/v1/workspaces/" + ws + "/runs?status=COMPLETED").andExpect(jsonPath("$[0].id").value(done)).andExpect(jsonPath("$.length()").value(1));
        api.get(u("viewer"), "/api/v1/workspaces/" + ws + "/runs?status=FINDINGS_AVAILABLE").andExpect(jsonPath("$[0].id").value(resting));
        api.get(u("viewer"), "/api/v1/workspaces/" + ws + "/runs").andExpect(jsonPath("$.length()").value(2));
    }

    private org.springframework.test.web.servlet.ResultActions post(String key, String b) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + ws + "/runs").header("X-Devforge-User", u("owner")).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(b));
    }

    private int runCount() { return jdbc.sql("select count(*) from run where workspace_id = ?::uuid").param(ws).query(Integer.class).single(); }
}
