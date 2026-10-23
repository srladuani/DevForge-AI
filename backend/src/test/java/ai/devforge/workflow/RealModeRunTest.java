package ai.devforge.workflow;

import static ai.devforge.testsupport.ScriptedAnthropicServer.failure;
import static ai.devforge.testsupport.ScriptedAnthropicServer.toolUse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.ScriptedAnthropicServer;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The platform configured for REAL agents, with the Claude adapter talking to a scripted local server. This exercises the real adapter, the
 * real investigation loop, the vault, the workers and the state machine together. It proves our side; it does not prove how Claude behaves.
 */
@TestPropertySource(properties = {"devforge.agent.mode=real", "devforge.workflow.queue-name=real-mode", "devforge.agent.max-tool-calls=2"})
class RealModeRunTest extends WorkflowTestBase {
    private static final String ADMIN = "admin@example.test";
    private static final String BO = "src/main/java/com/acme/video/TrainingVideoBO.java";
    private final ScriptedAnthropicServer server = new ScriptedAnthropicServer();
    private String providerId;

    @Override
    protected void beforeKeys() throws Exception {
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
        String list = api.get(ADMIN, "/api/v1/admin/provider-instances?kind=AI_PROVIDER").andReturn().getResponse().getContentAsString();
        providerId = JsonPath.<List<String>>read(list, "$[?(@.adapterType=='ANTHROPIC')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"" + server.url() + "\"}").andExpect(status().isOk());
    }

    @AfterEach
    void restore() throws Exception {
        if (providerId != null) api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        server.close();
    }

    private String submit(String extraFinding) {
        return "{\"summary\":\"Videos are not filtered by role\",\"findings\":[{\"label\":\"VERIFIED\",\"text\":\"TrainingVideoBO.videosFor ignores its role argument\",\"confidence\":\"HIGH\","
            + "\"evidence\":[{\"repo\":\"api\",\"path\":\"" + BO + "\",\"startLine\":6,\"endLine\":7}]}" + extraFinding + "],"
            + "\"hypotheses\":[{\"text\":\"the controller passes the role but the BO drops it\",\"confidence\":\"MEDIUM\",\"supporting\":[\"BO line 6\"],\"contradicting\":[]}],\"unknowns\":[\"no runtime data\"]}";
    }

    private static String quoteJson(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    private String investigate(String text) throws Exception {
        return Api.json(rawCreate(u("owner"), body("INVESTIGATE_ONLY", text)).andExpect(status().isAccepted()), "$.id");
    }

    @Test
    void aRealInvestigationRunsTheWholePipelineUsingTheOwnersKeyAndOnlyTheOwnersKey() throws Exception {
        String cross = ",{\"label\":\"SUPPORTED\",\"text\":\"The admin portal calls /api/v1/training-videos\",\"confidence\":\"MEDIUM\",\"evidence\":[{\"repo\":\"admin-portal\",\"path\":\"src/api/videos.js\",\"startLine\":1,\"endLine\":1}]}";
        server.then(toolUse("t1", "repo_search_files", "{\"repo\":\"api\",\"keywords\":[\"video\",\"role\"]}", 400, 40))
            .then(toolUse("t2", "repo_read", "{\"repo\":\"api\",\"path\":\"" + BO + "\",\"start_line\":1,\"end_line\":9}", 800, 60))
            .then(toolUse("t3", "submit_findings", submit(cross), 1200, 300));
        String run = investigate(TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");

        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.agent.simulated").value(false)).andExpect(jsonPath("$.incomplete").value(false))
            .andExpect(jsonPath("$.inputTokens").value(2400)).andExpect(jsonPath("$.outputTokens").value(400)).andExpect(jsonPath("$.costUsd").doesNotExist());
        String findings = api.get(u("owner"), "/api/v1/runs/" + run + "/findings").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(findings, "$[*].text")).contains("TrainingVideoBO.videosFor ignores its role argument").noneMatch(t -> t.contains("[SIMULATED]"));
        assertThat(JsonPath.<List<Boolean>>read(findings, "$[*].evidence[*].verified")).isNotEmpty().allMatch(v -> v);
        assertThat(JsonPath.<List<String>>read(findings, "$[*].evidence[*].repoName")).contains("api", "admin-portal");
        assertThat(JsonPath.<List<String>>read(findings, "$[?(@.label=='VERIFIED')].text")).hasSize(1);

        String events = api.get(u("owner"), "/api/v1/runs/" + run + "/events").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(events, "$[?(@.type=='TOOL_CALL')].payload.tool")).containsExactly("repo_search_files", "repo_read");

        // the owner's own key, on every model call, and nowhere it should not be
        String ownerKey = "sim-key-owner-" + suffix;
        assertThat(server.messageRequests()).hasSize(3).extracting(ScriptedAnthropicServer.Recorded::apiKey).containsOnly(ownerKey);
        assertThat(server.requests().stream().filter(r -> r.path().equals("/v1/models")).count()).as("one verification call when the key was verified").isGreaterThanOrEqualTo(1);
        assertThat(events + findings).doesNotContain(ownerKey);
        String firstCall = server.messageRequests().getFirst().body();
        assertThat(firstCall).contains("UNTRUSTED DATA").contains("<ticket>").contains("Navigation videos show the same full list").contains("repo_search_files").contains("submit_findings");
        assertThat(firstCall).doesNotContain(ownerKey);

        act(u("owner"), run, "complete", null).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void onlyInvestigationIsAvailableWithARealModelForNow() throws Exception {
        rawCreate(u("owner"), body("PROPOSE_FIX", TICKET_TEXT)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MODE_NOT_AVAILABLE_FOR_PROVIDER"));
        rawCreate(u("owner"), body("FIX_AND_PATCH", TICKET_TEXT)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MODE_NOT_AVAILABLE_FOR_PROVIDER"));
        server.then(toolUse("s", "submit_findings", submit(""), 10, 5));
        String run = investigate(TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.nextActions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("request-fix"))));
        act(u("owner"), run, "request-fix", "{\"mode\":\"PROPOSE_FIX\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MODE_NOT_AVAILABLE_FOR_PROVIDER"));
        assertThat(statusOf(run)).isEqualTo("FINDINGS_AVAILABLE");
    }

    @Test
    void aRejectedKeyPausesTheRunWithoutRetryingAndTheReplacementKeyIsUsedOnResume() throws Exception {
        server.then(failure(401, "authentication_error", "invalid x-api-key"));
        String run = investigate(TICKET_TEXT);
        awaitStatus(run, "AWAITING_INPUT");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.awaitReason").value("CREDENTIAL"));
        assertThat(server.messageRequests()).as("an invalid key is never retried").hasSize(1);

        String connId = Api.json(api.get(u("owner"), "/api/v1/me/connections"), "$[0].id");
        api.delete(u("owner"), "/api/v1/me/connections/" + connId).andExpect(status().isNoContent());
        api.post(u("owner"), "/api/v1/me/connections", "{\"kind\":\"AI_PROVIDER\",\"providerType\":\"ANTHROPIC\",\"authMethod\":\"API_KEY\",\"label\":\"new\",\"secret\":\"sk-replacement-key-0001\"}").andExpect(status().isCreated());
        String newId = Api.json(api.get(u("owner"), "/api/v1/me/connections"), "$[0].id");
        api.post(u("owner"), "/api/v1/me/connections/" + newId + "/verify", null).andExpect(jsonPath("$.status").value("VALID"));
        server.then(toolUse("s", "submit_findings", submit(""), 100, 20));
        act(u("owner"), run, "resume", null).andExpect(status().isOk());
        awaitStatus(run, "FINDINGS_AVAILABLE");
        assertThat(server.messageRequests().getLast().apiKey()).isEqualTo("sk-replacement-key-0001");
    }

    @Test
    void aTransientProviderFailureIsRetriedOnceAndThenSucceeds() throws Exception {
        server.then(failure(429, "rate_limit_error", "slow down")).then(toolUse("s", "submit_findings", submit(""), 100, 20));
        String run = investigate(TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        assertThat(server.messageRequests()).hasSize(2);
        assertThat(eventTypes(run)).contains("RETRYING");
    }

    @Test
    void aProviderOutageFailsTheRunRetryablyAfterThreeAttempts() throws Exception {
        server.then(failure(500, "api_error", "boom")).then(failure(500, "api_error", "boom")).then(failure(500, "api_error", "boom"));
        String run = investigate(TICKET_TEXT);
        awaitStatus(run, "FAILED");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.failure.code").value("MODEL_UNAVAILABLE")).andExpect(jsonPath("$.failure.retryable").value(true));
        assertThat(server.messageRequests()).hasSize(3);
    }

    @Test
    void aBudgetLimitStillDeliversWhatWasFoundAndSaysTheRunIsIncomplete() throws Exception {
        server.then(toolUse("a", "repo_grep", "{\"repo\":\"api\",\"term\":\"video\"}", 10, 5)).then(toolUse("b", "repo_grep", "{\"repo\":\"api\",\"term\":\"role\"}", 10, 5))
            .then(toolUse("c", "repo_grep", "{\"repo\":\"api\",\"term\":\"navigation\"}", 10, 5)).then(toolUse("d", "submit_findings", submit(""), 10, 5));
        String run = investigate(TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.incomplete").value(true));
        assertThat(eventTypes(run)).contains("INVESTIGATION_INCOMPLETE");
        assertThat(api.get(u("owner"), "/api/v1/runs/" + run + "/artifacts").andReturn().getResponse().getContentAsString()).contains("\"incomplete\":true");
        String findings = api.get(u("owner"), "/api/v1/runs/" + run + "/findings").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(findings, "$[*].text")).isNotEmpty();
    }

    @Test
    void theModelPickerOffersWhatTheKeyCanActuallyRunAndFlagsTheRest() throws Exception {
        server.modelsReply(new ScriptedAnthropicServer.Reply(200, "{\"data\":[{\"id\":\"claude-sonnet-5-5-20260101\"},{\"id\":\"claude-opus-5-5\"},{\"id\":\"claude-future-9\"}]}", 0));
        String connId = Api.json(api.get(u("owner"), "/api/v1/me/connections"), "$[0].id");
        api.post(u("owner"), "/api/v1/me/connections/" + connId + "/verify", null).andExpect(jsonPath("$.status").value("VALID")).andExpect(jsonPath("$.statusMessage").value(org.hamcrest.Matchers.containsString("3 model(s)")));
        api.get(u("owner"), "/api/v1/agent-providers").andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].availability").value("REAL"))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].models[?(@.id=='claude-sonnet-5-5')].available").value(true))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].models[?(@.id=='claude-haiku-5-5')].available").value(false))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].models[?(@.id=='claude-haiku-5-5')].unavailableReason").value("Not available to your key"))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].models[?(@.id=='claude-future-9')].available").value(true));
        // an unavailable model cannot be chosen, a discovered one can
        rawCreate(u("owner"), body("INVESTIGATE_ONLY", TICKET_TEXT).replace("claude-sonnet-5-5", "claude-haiku-5-5")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MODEL_NOT_AVAILABLE"));
        server.then(toolUse("s", "submit_findings", submit(""), 10, 5));
        rawCreate(u("owner"), body("INVESTIGATE_ONLY", TICKET_TEXT).replace("claude-sonnet-5-5", "claude-future-9")).andExpect(status().isAccepted());
    }
}
