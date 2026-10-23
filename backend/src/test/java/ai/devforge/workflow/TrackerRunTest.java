package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.FakeMcpServer;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Tickets read through an MCP server (a local fake): the real client, adapter, verifier, vault, workers and state machine together.
 * It proves our side of the protocol; it does not prove how Atlassian's service behaves.
 */
@TestPropertySource(properties = {"devforge.workflow.queue-name=tracker-test", "devforge.workflow.retry-backoff-millis=10"})
class TrackerRunTest extends WorkflowTestBase {
    private static final String ADMIN = "admin@example.test";
    private final FakeMcpServer mcp = new FakeMcpServer().issue("PBX-7", FakeMcpServer.sampleIssue("PBX-7", "Navigation videos show the same list for every role"));
    private String providerId;

    @Override
    protected void beforeKeys() throws Exception {
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
        String list = api.get(ADMIN, "/api/v1/admin/provider-instances?kind=TRACKER").andReturn().getResponse().getContentAsString();
        providerId = JsonPath.<List<String>>read(list, "$[?(@.adapterType=='JIRA_MCP')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"" + mcp.url() + "\"}").andExpect(status().isOk());
    }

    @AfterEach
    void restore() throws Exception {
        if (providerId != null) api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        mcp.close();
    }

    private String trackerConnection(String who, String secret) throws Exception {
        String id = Api.json(api.post(u(who), "/api/v1/me/connections", "{\"kind\":\"TRACKER\",\"providerType\":\"JIRA_MCP\",\"authMethod\":\"PAT\",\"label\":\"jira\",\"secret\":\"" + secret + "\"}")
            .andExpect(status().isCreated()), "$.id");
        return id;
    }

    private void verified(String who, String secret) throws Exception {
        String id = trackerConnection(who, secret);
        api.post(u(who), "/api/v1/me/connections/" + id + "/verify", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"));
    }

    private void bindTracker() throws Exception {
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/tracker", "{\"providerType\":\"JIRA_MCP\"}").andExpect(status().isOk());
    }

    private String ticketBody(String key, String text) {
        String t = text == null ? "" : ",\"text\":" + quote(text);
        return "{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"TICKET\",\"ref\":\"" + key + "\"" + t + "},\"repositoryIds\":[\"" + backendId + "\",\"" + webId + "\"],\"agent\":" + agent() + "}";
    }

    @Test
    void aTicketIsFetchedWithTheOwnersOwnConnectionAndNormalisedWithoutPeopleOrNoise() throws Exception {
        verified("owner", "owner@example.test:tok-valid");
        bindTracker();
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tracker").andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.providerType").value("JIRA_MCP"));

        String preview = api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(preview, "$.title")).startsWith("Navigation videos");
        assertThat(preview).doesNotContain("example.test").doesNotContain("avatar").doesNotContain("reporter");
        assertThat(JsonPath.<List<Boolean>>read(preview, "$.comments[*].routine")).containsExactly(true, false);

        String run = Api.json(rawCreate(u("owner"), ticketBody("PBX-7", null)).andExpect(status().isAccepted()), "$.id");
        awaitStatus(run, "FINDINGS_AVAILABLE");
        String view = api.get(u("owner"), "/api/v1/runs/" + run).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(view, "$.title")).startsWith("PBX-7: Navigation videos");
        String text = JsonPath.read(view, "$.inputs[0].text");
        assertThat(text).contains("Steps to Reproduce").contains("TrainingVideo list endpoint ignores the role").doesNotContain("Daily scrum").doesNotContain("example.test");
        assertThat(eventTypes(run)).contains("TICKET_FETCHED");
        assertThat(mcp.calls()).allSatisfy(c -> assertThat(c.tool()).isIn("getJiraIssue", "getAccessibleAtlassianResources"));
        assertThat(mcp.calls()).extracting(FakeMcpServer.Call::authorization).allMatch(a -> a.startsWith("Basic "));
    }

    @Test
    void pastedTextStillWorksAndNeverTouchesTheTracker() throws Exception {
        String run = Api.json(rawCreate(u("owner"), ticketBody("PBX-7", TICKET_TEXT)).andExpect(status().isAccepted()), "$.id");
        awaitStatus(run, "FINDINGS_AVAILABLE");
        assertThat(mcp.calls()).isEmpty();
        assertThat(eventTypes(run)).doesNotContain("TICKET_FETCHED");
    }

    @Test
    void withoutASetupTheCreateCallSaysWhatIsMissingInsteadOfStartingARunThatCannotWork() throws Exception {
        rawCreate(u("owner"), ticketBody("PBX-7", null)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRACKER_NOT_CONFIGURED"));
        bindTracker();
        rawCreate(u("owner"), ticketBody("PBX-7", null)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRACKER_NOT_CONFIGURED"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("connection")));
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void onlyProjectAdminsSetTheTrackerAndSecretsStayWriteOnly() throws Exception {
        api.put(u("operator"), "/api/v1/workspaces/" + ws + "/tracker", "{\"providerType\":\"JIRA_MCP\"}").andExpect(status().isForbidden());
        api.put(u("outsider"), "/api/v1/workspaces/" + ws + "/tracker", "{\"providerType\":\"JIRA_MCP\"}").andExpect(status().isNotFound());
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/tracker", "{\"providerType\":\"not valid\"}").andExpect(status().isBadRequest());
        api.get(u("viewer"), "/api/v1/workspaces/" + ws + "/tracker").andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        String id = trackerConnection("owner", "owner@example.test:tok-valid");
        assertThat(api.get(u("owner"), "/api/v1/me/connections").andReturn().getResponse().getContentAsString()).doesNotContain("tok-valid");
        api.delete(u("owner"), "/api/v1/me/connections/" + id).andExpect(status().isNoContent());
    }

    @Test
    void aRejectedCredentialPausesTheRunAndTheFixedOneResumesIt() throws Exception {
        verified("owner", "owner@example.test:tok-valid");
        bindTracker();
        mcp.accepts("tok-rotated");
        String run = Api.json(rawCreate(u("owner"), ticketBody("PBX-7", null)).andExpect(status().isAccepted()), "$.id");
        awaitStatus(run, "AWAITING_INPUT");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.awaitReason").value("CREDENTIAL")).andExpect(jsonPath("$.resumeState").value("INPUT_RETRIEVAL"));

        String old = Api.json(api.get(u("owner"), "/api/v1/me/connections?kind=TRACKER"), "$[0].id");
        api.delete(u("owner"), "/api/v1/me/connections/" + old).andExpect(status().isNoContent());
        verified("owner", "owner@example.test:tok-rotated");
        act(u("owner"), run, "resume", null).andExpect(status().isOk());
        awaitStatus(run, "FINDINGS_AVAILABLE");
        assertThat(eventTypes(run)).contains("TICKET_FETCHED");
    }

    @Test
    void aMissingTicketAsksForThePastedTextAndContinuesWithIt() throws Exception {
        verified("owner", "owner@example.test:tok-valid");
        bindTracker();
        String run = Api.json(rawCreate(u("owner"), ticketBody("PBX-99", null)).andExpect(status().isAccepted()), "$.id");
        awaitStatus(run, "AWAITING_INPUT");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.awaitReason").value("CLARIFICATION"));
        int callsBefore = mcp.calls().size();
        act(u("owner"), run, "answer", "{\"text\":" + quote(TICKET_TEXT) + "}").andExpect(status().isOk());
        awaitStatus(run, "FINDINGS_AVAILABLE");
        assertThat(mcp.calls().size()).as("the tracker is not asked again once the owner pasted the ticket").isEqualTo(callsBefore);
    }

    @Test
    void aTrackerThatStaysDownFailsTheRunRetryablyAndFabricatesNothing() throws Exception {
        verified("owner", "owner@example.test:tok-valid");
        bindTracker();
        mcp.failNext(1000);
        String run = Api.json(rawCreate(u("owner"), ticketBody("PBX-7", null)).andExpect(status().isAccepted()), "$.id");
        awaitStatus(run, "FAILED");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.failure.code").value("TRACKER_UNAVAILABLE")).andExpect(jsonPath("$.failure.retryable").value(true))
            .andExpect(jsonPath("$.inputs[0].text").value(""));
        assertThat(eventTypes(run)).contains("RETRYING").doesNotContain("TICKET_FETCHED");

        mcp.failNext(0);
        act(u("owner"), run, "resume", null).andExpect(status().isOk());
        awaitStatus(run, "FINDINGS_AVAILABLE");
    }

    @Test
    void severalSitesNeedTheProjectToSayWhichAndOneIsPickedByNameOrUrl() throws Exception {
        verified("owner", "owner@example.test:tok-valid");
        mcp.sites = "[{\"id\":\"cloud-1\",\"url\":\"https://acme.atlassian.net\",\"name\":\"acme\"},{\"id\":\"cloud-2\",\"url\":\"https://beta.atlassian.net\",\"name\":\"beta\"}]";
        bindTracker();
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRACKER_AMBIGUOUS_SITE"));
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/tracker", "{\"providerType\":\"JIRA_MCP\",\"siteHint\":\"beta\"}").andExpect(status().isOk());
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isOk());
        assertThat(mcp.calls().getLast().arguments().path("cloudId").asString()).isEqualTo("cloud-2");
    }

    @Test
    void aWrongCredentialIsReportedAsInvalidWhenVerified() throws Exception {
        String id = trackerConnection("owner", "owner@example.test:wrong");
        api.post(u("owner"), "/api/v1/me/connections/" + id + "/verify", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVALID"));
    }
}
