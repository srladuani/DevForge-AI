package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.FakeMcpServer;
import ai.devforge.testsupport.FakeOAuthServer;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Signing in to Jira with single sign-on (OAuth 2.1 + PKCE + dynamic client registration) against fake login and MCP services: the real discovery,
 * registration, authorization-address building, code exchange, encrypted storage, token renewal and ticket reading. It proves our side of the
 * protocol; it cannot prove how Atlassian's login behaves (for example which redirect addresses it accepts).
 */
@TestPropertySource(properties = {"devforge.workflow.queue-name=tracker-oauth-test", "devforge.oauth.allow-insecure-localhost=true", "devforge.oauth.redirect-base=http://localhost:8080"})
class TrackerOAuthTest extends WorkflowTestBase {
    private static final String ADMIN = "admin@example.test";
    private final FakeOAuthServer idp = new FakeOAuthServer();
    private final FakeMcpServer mcp = new FakeMcpServer().issue("PBX-7", FakeMcpServer.sampleIssue("PBX-7", "Navigation videos show the same list for every role")).accepts("at-1");
    private String providerId;

    @Override
    protected void beforeKeys() throws Exception {
        mcp.authorizationServer = idp.url();
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
        String list = api.get(ADMIN, "/api/v1/admin/provider-instances?kind=TRACKER").andReturn().getResponse().getContentAsString();
        providerId = JsonPath.<List<String>>read(list, "$[?(@.adapterType=='JIRA_MCP')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"" + mcp.url() + "\"}").andExpect(status().isOk());
    }

    @AfterEach
    void restore() throws Exception {
        if (providerId != null) api.patch(ADMIN, "/api/v1/admin/provider-instances/" + providerId, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        mcp.close();
        idp.close();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> m = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            m.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Starts a sign-in as the owner and returns the parameters the browser would carry to the provider. */
    private Map<String, String> start() throws Exception {
        String url = Api.json(api.post(u("owner"), "/api/v1/me/oauth/JIRA_MCP/start", "{\"label\":\"Atlassian SSO\"}").andExpect(status().isOk()), "$.authorizationUrl");
        Map<String, String> q = query(url);
        q.put("_url", url);
        idp.expectedChallenge = q.get("code_challenge");
        return q;
    }

    private String callback(String state, String code, String error) throws Exception {
        var req = get("/api/v1/oauth/callback"); // a browser redirect: no login header
        if (state != null) req.param("state", state);
        if (code != null) req.param("code", code);
        if (error != null) req.param("error", error);
        var res = mvc.perform(req).andExpect(status().isFound()).andReturn().getResponse();
        return res.getHeader("Location");
    }

    private String trackerConnectionId() throws Exception {
        return JsonPath.<List<String>>read(api.get(u("owner"), "/api/v1/me/connections").andReturn().getResponse().getContentAsString(), "$[?(@.kind=='TRACKER')].id").getFirst();
    }

    private void bindTracker() throws Exception { api.put(u("owner"), "/api/v1/workspaces/" + ws + "/tracker", "{\"providerType\":\"JIRA_MCP\"}").andExpect(status().isOk()); }

    @Test
    void signingInWithSingleSignOnCreatesAnEncryptedConnectionThatReadsTicketsWithoutAnyPastedToken() throws Exception {
        Map<String, String> q = start();
        assertThat(q.get("_url")).startsWith(idp.url() + "/authorize?");
        assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", "client-1").containsEntry("code_challenge_method", "S256").containsEntry("redirect_uri", "http://localhost:8080/api/v1/oauth/callback");
        assertThat(q.get("resource")).isEqualTo(mcp.url());
        assertThat(q.get("scope")).as("read-only scopes and offline access, never write scopes").contains("offline_access").contains("read:jira:agent-interface").doesNotContain("write");
        assertThat(q.get("state")).hasSizeGreaterThan(30);
        assertThat(idp.registrations().getFirst()).contains("\"token_endpoint_auth_method\":\"none\"").contains("http://localhost:8080/api/v1/oauth/callback");

        assertThat(callback(q.get("state"), "code-1", null)).isEqualTo("http://localhost:8080/connections?oauth=success");
        String list = api.get(u("owner"), "/api/v1/me/connections").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$[?(@.kind=='TRACKER')].authMethod")).containsExactly("OAUTH");
        assertThat(JsonPath.<List<String>>read(list, "$[?(@.kind=='TRACKER')].status")).containsExactly("VALID");
        assertThat(list).doesNotContain("at-1").doesNotContain("rt-1").doesNotContain("access_token");
        assertThat(jdbc.sql("select encrypted_secret from connection where user_id = (select id from app_user where email = ?) and auth_method = 'OAUTH'").param(u("owner")).query(byte[].class).single())
            .as("tokens are stored encrypted").satisfies(b -> assertThat(new String(b, StandardCharsets.ISO_8859_1)).doesNotContain("at-1").doesNotContain("rt-1"));

        bindTracker();
        String preview = api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(preview, "$.title")).startsWith("Navigation videos");
        assertThat(mcp.calls().getFirst().authorization()).isEqualTo("Bearer at-1");
    }

    @Test
    void anAccessTokenThatIsAboutToExpireIsRenewedOnceStoredAndUsedAndARevokedSignInSaysToSignInAgain() throws Exception {
        idp.expiresIn = 30; // under the renewal margin: the first use must renew
        callback(start().get("state"), "code-1", null); // verified with the first token
        mcp.accepts("at-2"); // from now on only the renewed token works
        bindTracker();
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isOk());
        assertThat(idp.refreshes()).isEqualTo(1);
        assertThat(mcp.calls().getFirst().authorization()).isEqualTo("Bearer at-2");
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isOk());
        assertThat(idp.refreshes()).as("the renewed token was stored, so it is not renewed again").isEqualTo(1);

        // the provider revokes the sign-in: the next renewal fails and the person is told to sign in again
        api.delete(u("owner"), "/api/v1/me/connections/" + trackerConnectionId()).andExpect(status().isNoContent());
        idp.currentAccess = "at-1";
        idp.currentRefresh = "rt-1";
        idp.expiresIn = 30;
        mcp.accepts("at-1");
        callback(start().get("state"), "code-1", null);
        idp.refreshFails = true;
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/tickets/PBX-7").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRACKER_AUTH_INVALID"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsStringIgnoringCase("sign in again")));
    }

    @Test
    void aStateWorksOnceBelongsToItsOwnerAndABadOrDeniedReturnJustShowsAMessage() throws Exception {
        Map<String, String> q = start();
        assertThat(callback("not-a-real-state", "code-1", null)).contains("oauth=error").contains("no+longer+valid");
        assertThat(callback(null, "code-1", null)).contains("oauth=error");
        assertThat(callback(q.get("state"), "wrong-code", null)).as("a code the provider refuses").contains("oauth=error");
        assertThat(callback(q.get("state"), "code-1", null)).as("the state was used up by the first attempt").contains("oauth=error");
        Map<String, String> q2 = start();
        assertThat(callback(q2.get("state"), null, "access_denied")).contains("oauth=error").contains("access_denied");
        assertThat(JsonPath.<List<String>>read(api.get(u("owner"), "/api/v1/me/connections").andReturn().getResponse().getContentAsString(), "$[?(@.kind=='TRACKER')]")).isEmpty();

        Map<String, String> q3 = start();
        jdbc.sql("update oauth_flow set expires_at = now() - interval '1 minute' where state = ?").param(q3.get("state")).update();
        assertThat(callback(q3.get("state"), "code-1", null)).contains("oauth=error").contains("no+longer+valid");
    }

    @Test
    void metadataThatPointsAtAnotherOrganisationOrAPlainHttpAddressIsRefusedBeforeAnythingIsSent() throws Exception {
        idp.authorizeHost = "http://evil.example"; // the authorization endpoint is on another organisation and not https
        api.post(u("owner"), "/api/v1/me/oauth/JIRA_MCP/start", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OAUTH_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not allowed")));
        idp.authorizeHost = null;
        mcp.authorizationServer = "https://auth.evil.example";
        api.post(u("owner"), "/api/v1/me/oauth/JIRA_MCP/start", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OAUTH_UNAVAILABLE"));
        api.post(u("owner"), "/api/v1/me/oauth/GITHUB/start", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OAUTH_NOT_SUPPORTED"));
    }

    @Test
    void verifyWorksForASignedInConnectionAndTheCallbackNeedsNoLoginButStartDoes() throws Exception {
        callback(start().get("state"), "code-1", null);
        String id = trackerConnectionId();
        api.post(u("owner"), "/api/v1/me/connections/" + id + "/verify", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/me/oauth/JIRA_MCP/start").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        api.post(u("outsider"), "/api/v1/me/connections/" + id + "/verify", null).andExpect(status().isNotFound());
    }
}
