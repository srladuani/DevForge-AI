package ai.devforge.workflow;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.FixtureRepos;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A project with a backend and a web frontend repository, an owner with a verified (simulated) Claude key, and people in each role.
 * Runs execute for real: the database, the job queue, the workers and the state machine are all the production ones.
 */
public abstract class WorkflowTestBase extends IntegrationTest {
    protected static final String TICKET_TEXT = """
        Navigation videos show the same full list for every role.
        Expected: each role only sees the videos assigned to it. The TrainingVideo list at /api/v1/training-videos returns everything.""";

    @TempDir protected Path tmp;
    protected Api api;
    protected final String suffix = UUID.randomUUID().toString().substring(0, 8);
    protected String ws;
    protected String backendId;
    protected String webId;

    protected String u(String name) { return name + "-" + suffix + "@example.test"; }

    /** Runs before any key is saved or verified; real-mode tests point the provider at a local server here. */
    protected void beforeKeys() throws Exception {}

    @BeforeEach
    void setUpProject() throws Exception {
        api = new Api(mvc);
        beforeKeys();
        for (String n : new String[] {"owner", "approver", "operator", "viewer", "outsider"}) api.get(u(n), "/api/v1/workspaces").andExpect(status().isOk());
        ws = Api.json(api.post(u("owner"), "/api/v1/workspaces", "{\"name\":\"wf\",\"kind\":\"SUPPORT\"}").andExpect(status().isCreated()), "$.id");
        backendId = Api.json(api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories",
            "{\"path\":\"" + FixtureRepos.gradleSpring(tmp.resolve("backend")) + "\",\"name\":\"api\",\"role\":\"BACKEND\"}").andExpect(status().isCreated()), "$.repositoryId");
        webId = Api.json(api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories",
            "{\"path\":\"" + FixtureRepos.reactWebpack(tmp.resolve("web")) + "\",\"name\":\"admin-portal\",\"role\":\"WEB_FRONTEND\"}").andExpect(status().isCreated()), "$.repositoryId");
        for (String[] m : new String[][] {{"approver", "APPROVER"}, {"operator", "OPERATOR"}, {"viewer", "VIEWER"}}) {
            api.put(u("owner"), "/api/v1/workspaces/" + ws + "/members", "{\"email\":\"" + u(m[0]) + "\",\"role\":\"" + m[1] + "\"}").andExpect(status().isOk());
        }
        giveClaudeKey("owner");
        giveClaudeKey("operator");
    }

    protected void giveClaudeKey(String who) throws Exception {
        String conn = Api.json(api.post(u(who), "/api/v1/me/connections", "{\"kind\":\"AI_PROVIDER\",\"providerType\":\"ANTHROPIC\",\"authMethod\":\"API_KEY\",\"label\":\"k\",\"secret\":\"sim-key-" + who + "-" + suffix + "\"}")
            .andExpect(status().isCreated()), "$.id");
        api.post(u(who), "/api/v1/me/connections/" + conn + "/verify", null).andExpect(status().isOk());
    }

    protected String agent() { return "{\"provider\":\"ANTHROPIC\",\"model\":\"claude-sonnet-5-5\"}"; }

    protected String body(String mode, String text, String... repoIds) {
        String repos = String.join(",", Arrays.stream(repoIds.length == 0 ? new String[] {backendId, webId} : repoIds).map(r -> "\"" + r + "\"").toList());
        return "{\"mode\":\"" + mode + "\",\"input\":{\"kind\":\"PROMPT\",\"text\":" + quote(text) + "},\"repositoryIds\":[" + repos + "],\"agent\":" + agent() + "}";
    }

    protected static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    protected ResultActions rawCreate(String user, String body) throws Exception { return api.post(user, "/api/v1/workspaces/" + ws + "/runs", body); }

    protected String createRun(String mode, String text) throws Exception {
        return Api.json(rawCreate(u("owner"), body(mode, text)).andExpect(status().isAccepted()), "$.id");
    }

    protected String field(String user, String runId, String path) throws Exception { return Api.json(api.get(user, "/api/v1/runs/" + runId), path); }

    protected String statusOf(String runId) throws Exception { return field(u("owner"), runId, "$.status"); }

    /** Waits until the run reaches one of the statuses (workers are asynchronous), failing fast if it lands somewhere unexpected and final. */
    protected void awaitStatus(String runId, String... wanted) {
        List<String> ok = List.of(wanted);
        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(60)).until(() -> {
            String s = statusOf(runId);
            if (!ok.contains(s) && List.of("COMPLETED", "CANCELLED").contains(s)) throw new AssertionError("Run ended as " + s + " while waiting for " + ok);
            if (!ok.contains(s) && s.equals("FAILED")) throw new AssertionError("Run FAILED while waiting for " + ok + ": " + field(u("owner"), runId, "$.failure.code"));
            return ok.contains(s);
        });
    }

    protected ResultActions act(String user, String runId, String action, String json) throws Exception {
        return api.post(user, "/api/v1/runs/" + runId + "/actions/" + action, json);
    }

    protected String pendingApprovalId(String runId) throws Exception {
        return Api.json(api.get(u("approver"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + runId + "&status=PENDING").andExpect(status().isOk()), "$[0].id");
    }

    protected String pendingApprovalHash(String runId) throws Exception {
        return Api.json(api.get(u("approver"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + runId + "&status=PENDING"), "$[0].artifactHash");
    }

    protected ResultActions decide(String user, String approvalId, String decision, String hash) throws Exception {
        return api.post(user, "/api/v1/approvals/" + approvalId + "/decision", "{\"decision\":\"" + decision + "\",\"artifactHash\":\"" + hash + "\"}");
    }

    protected List<String> eventTypes(String runId) throws Exception {
        String json = api.get(u("owner"), "/api/v1/runs/" + runId + "/events").andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$[*].type");
    }

    protected List<String> statesVisited(String runId) throws Exception {
        String json = api.get(u("owner"), "/api/v1/runs/" + runId + "/events").andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$[?(@.type=='STATE_CHANGED')].payload.to");
    }
}
