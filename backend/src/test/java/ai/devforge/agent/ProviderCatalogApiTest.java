package ai.devforge.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import ai.devforge.identity.Users;
import ai.devforge.platform.DomainException;
import ai.devforge.testsupport.Api;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProviderCatalogApiTest extends IntegrationTest {
    @Autowired ModelCatalog catalog;
    @Autowired Users users;
    Api api;
    final String suffix = UUID.randomUUID().toString().substring(0, 8);
    static final String ADMIN = "admin@example.test";

    String u(String n) { return n + "-" + suffix + "@example.test"; }

    UUID id(String email) { return users.findByEmail(email).orElseThrow().id(); }

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
    }

    String addKey(String user, String provider, String secret) throws Exception {
        return Api.json(api.post(user, "/api/v1/me/connections", "{\"kind\":\"AI_PROVIDER\",\"providerType\":\"" + provider + "\",\"authMethod\":\"API_KEY\",\"label\":\"k\",\"secret\":\"" + secret + "\"}")
            .andExpect(status().isCreated()), "$.id");
    }

    @Test
    void seededProvidersAreVisibleAndOnlyClaudeHasAnAdapterToday() throws Exception {
        api.get(u("alice"), "/api/v1/agent-providers").andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].availability").value("SIMULATED"))
            .andExpect(jsonPath("$[?(@.provider=='OPENAI')].availability").value("PLANNED"))
            .andExpect(jsonPath("$[?(@.provider=='GEMINI')].availability").value("PLANNED"))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].selectable").value(false))
            .andExpect(jsonPath("$[?(@.provider=='OPENAI')].models.length()").value(0));
    }

    @Test
    void claudeBecomesSelectableOnlyAfterTheUsersOwnKeyIsVerified() throws Exception {
        String user = u("bob");
        api.get(user, "/api/v1/workspaces").andExpect(status().isOk());
        assertThatThrownBy(() -> catalog.select(id(user), null, new AgentSelectionRequest("ANTHROPIC", "claude-sonnet-5-5", null)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NO_USABLE_CONNECTION"));

        String conn = addKey(user, "anthropic", "sim-key-bob-1234");
        assertThatThrownBy(() -> catalog.select(id(user), null, new AgentSelectionRequest("ANTHROPIC", "claude-sonnet-5-5", null)))
            .as("unverified key is not usable").isInstanceOf(DomainException.class);

        api.post(user, "/api/v1/me/connections/" + conn + "/verify", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"))
            .andExpect(jsonPath("$.statusMessage").value(org.hamcrest.Matchers.containsString("SIMULATED")));
        api.get(user, "/api/v1/agent-providers").andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].selectable").value(true))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].models[?(@.id=='claude-opus-5-5')].tier").value("STRONG"))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].models[?(@.id=='claude-sonnet-5-5')].tier").value("BALANCED"))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].models[?(@.id=='claude-haiku-5-5')].tier").value("FAST"));

        AgentSelection sel = catalog.select(id(user), null, new AgentSelectionRequest("anthropic", "claude-opus-5-5", "claude-haiku-5-5"));
        assertThat(sel.provider()).isEqualTo("ANTHROPIC");
        assertThat(sel.model()).isEqualTo("claude-opus-5-5");
        assertThat(sel.fastModel()).isEqualTo("claude-haiku-5-5");
        assertThat(sel.simulated()).isTrue();
        assertThat(sel.connectionId().toString()).isEqualTo(conn);
    }

    @Test
    void keysAreNotSharedBetweenUsers() throws Exception {
        String a = u("carol"), b = u("dave");
        api.get(b, "/api/v1/workspaces").andExpect(status().isOk());
        String conn = addKey(a, "ANTHROPIC", "sim-key-carol-1234");
        api.post(a, "/api/v1/me/connections/" + conn + "/verify", null).andExpect(status().isOk());
        assertThatThrownBy(() -> catalog.select(id(b), null, new AgentSelectionRequest("ANTHROPIC", "claude-sonnet-5-5", null)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NO_USABLE_CONNECTION"));
    }

    @Test
    void tooShortKeyVerifiesAsInvalid() throws Exception {
        String user = u("erin");
        String conn = addKey(user, "ANTHROPIC", "short");
        api.post(user, "/api/v1/me/connections/" + conn + "/verify", null).andExpect(jsonPath("$.status").value("INVALID"));
    }

    @Test
    void plannedProvidersAndUnknownModelsAreRejectedWithActionableCodes() throws Exception {
        String user = u("frank");
        String conn = addKey(user, "ANTHROPIC", "sim-key-frank-1234");
        api.post(user, "/api/v1/me/connections/" + conn + "/verify", null).andExpect(status().isOk());
        addKey(user, "OPENAI", "sk-openai-frank-1234567890");
        assertThatThrownBy(() -> catalog.select(id(user), null, new AgentSelectionRequest("OPENAI", "gpt-anything", null)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_NOT_IMPLEMENTED"));
        assertThatThrownBy(() -> catalog.select(id(user), null, new AgentSelectionRequest("ANTHROPIC", "claude-imaginary", null)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("MODEL_NOT_AVAILABLE"));
        assertThatThrownBy(() -> catalog.select(id(user), null, new AgentSelectionRequest("NOPE", "x", null)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_NOT_FOUND"));
    }

    @Test
    void projectPolicyCanRestrictProviders() throws Exception {
        String owner = u("gina");
        String ws = Api.json(api.post(owner, "/api/v1/workspaces", "{\"name\":\"restricted\",\"kind\":\"SUPPORT\"}").andExpect(status().isCreated()), "$.id");
        String conn = addKey(owner, "ANTHROPIC", "sim-key-gina-1234");
        api.post(owner, "/api/v1/me/connections/" + conn + "/verify", null).andExpect(status().isOk());
        api.patch(owner, "/api/v1/workspaces/" + ws, "{\"policy\":{\"allowedProviders\":[\"GEMINI\"],\"requireDifferentApprover\":false,\"maxRunCostUsd\":2,\"maxConcurrentRuns\":2}}").andExpect(status().isOk());
        api.get(owner, "/api/v1/agent-providers?workspaceId=" + ws).andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].allowedByProject").value(false))
            .andExpect(jsonPath("$[?(@.provider=='ANTHROPIC')].selectable").value(false));
        assertThatThrownBy(() -> catalog.select(id(owner), UUID.fromString(ws), new AgentSelectionRequest("ANTHROPIC", "claude-sonnet-5-5", null)))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_NOT_ALLOWED"));
        api.get(u("outsider"), "/api/v1/agent-providers?workspaceId=" + ws).andExpect(status().isNotFound());
    }
}
