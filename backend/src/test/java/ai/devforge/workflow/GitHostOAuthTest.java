package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.FakeBitbucketServer;
import ai.devforge.testsupport.FakeGitHubServer;
import ai.devforge.testsupport.FakeOAuthServer;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Signing in to Bitbucket Cloud and GitHub with an OAuth app the administrator registered (client id and secret in the server's configuration),
 * against a fake login service: real authorization addresses, client authentication, code exchange, encrypted storage, renewal. It proves our
 * side; it cannot prove how the real providers behave (callback address rules, token lifetimes).
 */
@TestPropertySource(properties = {"devforge.workflow.queue-name=githost-oauth-test", "devforge.oauth.allow-insecure-localhost=true", "devforge.oauth.redirect-base=http://localhost:8080",
    "devforge.oauth.providers.bitbucket-cloud.client-id=bb-client", "devforge.oauth.providers.bitbucket-cloud.client-secret=bb-secret",
    "devforge.oauth.providers.github.client-id=gh-client", "devforge.oauth.providers.github.client-secret=gh-secret"})
class GitHostOAuthTest extends WorkflowTestBase {
    private static final String ADMIN = "admin@example.test";
    private static final FakeOAuthServer IDP = new FakeOAuthServer();
    private final FakeBitbucketServer bb = new FakeBitbucketServer();
    private final FakeGitHubServer gh = new FakeGitHubServer();
    private String bitbucketInstance, githubInstance;

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry r) {
        r.add("devforge.oauth.providers.bitbucket-cloud.authorize-url", () -> IDP.url() + "/authorize");
        r.add("devforge.oauth.providers.bitbucket-cloud.token-url", () -> IDP.url() + "/token");
        r.add("devforge.oauth.providers.github.authorize-url", () -> IDP.url() + "/authorize");
        r.add("devforge.oauth.providers.github.token-url", () -> IDP.url() + "/token");
    }

    @Override
    protected void beforeKeys() throws Exception {
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
        String hosts = api.get(ADMIN, "/api/v1/admin/provider-instances?kind=GIT_HOST").andReturn().getResponse().getContentAsString();
        bitbucketInstance = JsonPath.<List<String>>read(hosts, "$[?(@.adapterType=='BITBUCKET_CLOUD')].id").getFirst();
        githubInstance = JsonPath.<List<String>>read(hosts, "$[?(@.adapterType=='GITHUB')].id").getFirst();
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + bitbucketInstance, "{\"baseUrl\":\"" + bb.url() + "\"}").andExpect(status().isOk());
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + githubInstance, "{\"baseUrl\":\"" + gh.url() + "\"}").andExpect(status().isOk());
        IDP.requirePkce = true;
        IDP.noLifetime = false;
        IDP.refreshFails = false;
        IDP.expiresIn = 3600;
        IDP.currentAccess = "at-1";
        IDP.currentRefresh = "rt-1";
    }

    @AfterEach
    void restore() throws Exception {
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + bitbucketInstance, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + githubInstance, "{\"baseUrl\":\"\"}").andExpect(status().isOk());
        bb.close();
        gh.close();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> m = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            m.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private Map<String, String> start(String type) throws Exception {
        Map<String, String> q = query(Api.json(api.post(u("owner"), "/api/v1/me/oauth/" + type + "/start", "{\"label\":\"SSO\"}").andExpect(status().isOk()), "$.authorizationUrl"));
        if (q.containsKey("code_challenge")) IDP.expectedChallenge = q.get("code_challenge");
        return q;
    }

    private String callback(String state) throws Exception {
        return mvc.perform(get("/api/v1/oauth/callback").param("state", state).param("code", "code-1")).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
    }

    private Map<String, Object> connection(String type) throws Exception {
        List<Map<String, Object>> l = JsonPath.read(api.get(u("owner"), "/api/v1/me/connections").andReturn().getResponse().getContentAsString(), "$[?(@.providerType=='" + type + "')]");
        assertThat(l).hasSize(1);
        return l.getFirst();
    }

    @Test
    void adaptersSayWhereSingleSignOnIsAvailable() throws Exception {
        String list = api.get(u("owner"), "/api/v1/adapter-types").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Boolean>>read(list, "$[?(@.type=='BITBUCKET_CLOUD')].singleSignOn")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(list, "$[?(@.type=='GITHUB')].singleSignOn")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(list, "$[?(@.type=='JIRA_MCP')].singleSignOn")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(list, "$[?(@.type=='ANTHROPIC')].singleSignOn")).containsExactly(false);
    }

    @Test
    void bitbucketSignInUsesTheAdministratorsAppAuthenticatesTheClientWithBasicAndRenewsTheTokenWithTheSameClient() throws Exception {
        IDP.requirePkce = false;
        IDP.expiresIn = 30; // under the renewal margin
        bb.acceptedBearer = "at-1";
        Map<String, String> q = start("BITBUCKET_CLOUD");
        assertThat(q).containsEntry("client_id", "bb-client").containsEntry("response_type", "code").containsEntry("redirect_uri", "http://localhost:8080/api/v1/oauth/callback").doesNotContainKeys("code_challenge", "scope");
        assertThat(callback(q.get("state"))).isEqualTo("http://localhost:8080/connections?oauth=success");
        Map<String, Object> c = connection("BITBUCKET_CLOUD");
        assertThat(c).containsEntry("kind", "GIT_HOST").containsEntry("authMethod", "OAUTH").containsEntry("status", "VALID");
        assertThat(api.get(u("owner"), "/api/v1/me/connections").andReturn().getResponse().getContentAsString()).doesNotContain("at-1").doesNotContain("rt-1").doesNotContain("bb-secret");
        String basic = "Basic " + Base64.getEncoder().encodeToString("bb-client:bb-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(IDP.tokenRequests().getLast()).startsWith(basic + "|").contains("grant_type=authorization_code").doesNotContain("bb-secret".concat("&"));

        bb.acceptedBearer = "at-2"; // only the renewed token works from now on
        api.post(u("owner"), "/api/v1/me/connections/" + c.get("id") + "/verify", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"));
        assertThat(IDP.refreshes()).isEqualTo(1);
        assertThat(IDP.tokenRequests().getLast()).startsWith(basic + "|").contains("grant_type=refresh_token");
    }

    @Test
    void gitHubSignInUsesPkceAndTheClientSecretInTheBodyAndATokenWithNoLifetimeIsNeverRenewed() throws Exception {
        IDP.noLifetime = true;
        gh.acceptedToken = "at-1";
        Map<String, String> q = start("GITHUB");
        assertThat(q).containsEntry("client_id", "gh-client").containsEntry("code_challenge_method", "S256").containsEntry("scope", "repo").containsKey("code_challenge");
        assertThat(callback(q.get("state"))).isEqualTo("http://localhost:8080/connections?oauth=success");
        Map<String, Object> c = connection("GITHUB");
        assertThat(c).containsEntry("authMethod", "OAUTH").containsEntry("status", "VALID");
        assertThat(IDP.tokenRequests().getLast()).startsWith("null|").contains("client_id=gh-client").contains("client_secret=gh-secret").contains("code_verifier=");
        api.post(u("owner"), "/api/v1/me/connections/" + c.get("id") + "/verify", null).andExpect(jsonPath("$.status").value("VALID"));
        api.post(u("owner"), "/api/v1/me/connections/" + c.get("id") + "/verify", null).andExpect(jsonPath("$.status").value("VALID"));
        assertThat(IDP.refreshes()).as("a token that does not expire is not renewed").isZero();
        assertThat(gh.requests().stream().map(FakeGitHubServer.Request::authorization)).allMatch(a -> a.equals("Bearer at-1"));
    }
}
