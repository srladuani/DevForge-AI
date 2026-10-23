package ai.devforge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import ai.devforge.testsupport.Api;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderAdminApiTest extends IntegrationTest {
    static final String ADMIN = "admin@example.test";
    Api api;
    final String suffix = UUID.randomUUID().toString().substring(0, 6);

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());
    }

    @Test
    void onlyAdminsManageProviderInstancesButAnyUserSeesAdapterTypes() throws Exception {
        api.get("user-" + suffix + "@example.test", "/api/v1/admin/provider-instances").andExpect(status().isForbidden());
        api.post("user-" + suffix + "@example.test", "/api/v1/admin/provider-instances", "{\"kind\":\"AI_PROVIDER\",\"adapterType\":\"OPENAI\",\"displayName\":\"x\"}").andExpect(status().isForbidden());
        api.get("user-" + suffix + "@example.test", "/api/v1/adapter-types").andExpect(status().isOk()).andExpect(jsonPath("$[?(@.type=='ANTHROPIC')].availability").value("SIMULATED"));
        api.get(ADMIN, "/api/v1/admin/provider-instances?kind=AI_PROVIDER").andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.adapterType=='ANTHROPIC')]").exists()).andExpect(jsonPath("$[?(@.adapterType=='JIRA_MCP')]").doesNotExist());
    }

    @Test
    void adminCanAddUpdateDisableAndRemoveAnInstanceWithoutCodeChanges() throws Exception {
        String name = "Corp gateway " + suffix;
        String id = Api.json(api.post(ADMIN, "/api/v1/admin/provider-instances", "{\"kind\":\"AI_PROVIDER\",\"adapterType\":\"OPENAI_COMPATIBLE\",\"displayName\":\"" + name
            + "\",\"baseUrl\":\"https://llm.corp.example.test/v1\",\"modelPolicy\":{\"allow\":[],\"deny\":[],\"tierOverrides\":{},\"customModels\":"
            + "[{\"id\":\"corp-large\",\"displayName\":\"Corp Large\",\"tier\":\"STRONG\",\"contextWindow\":128000,\"toolCalling\":true},"
            + "{\"id\":\"corp-chat-only\",\"displayName\":\"Corp Chat\",\"tier\":\"FAST\",\"contextWindow\":8000,\"toolCalling\":false}]}}")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.availability").value("PLANNED")), "$.id");
        api.post(ADMIN, "/api/v1/admin/provider-instances", "{\"kind\":\"AI_PROVIDER\",\"adapterType\":\"OPENAI_COMPATIBLE\",\"displayName\":\"" + name + "\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROVIDER_EXISTS"));

        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + id, "{\"displayName\":\"" + name + " v2\",\"enabled\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        api.get("user-" + suffix + "@example.test", "/api/v1/agent-providers").andExpect(jsonPath("$[?(@.displayName=='" + name + " v2')]").doesNotExist());
        api.patch(ADMIN, "/api/v1/admin/provider-instances/" + id, "{\"enabled\":true}").andExpect(status().isOk());
        api.get("user-" + suffix + "@example.test", "/api/v1/agent-providers").andExpect(jsonPath("$[?(@.displayName=='" + name + " v2')].models.length()").value(2))
            .andExpect(jsonPath("$[?(@.displayName=='" + name + " v2')].models[?(@.id=='corp-chat-only')].available").value(false))
            .andExpect(jsonPath("$[?(@.displayName=='" + name + " v2')].models[?(@.id=='corp-large')].tier").value("STRONG"));

        api.delete(ADMIN, "/api/v1/admin/provider-instances/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(true));
        api.delete(ADMIN, "/api/v1/admin/provider-instances/" + id).andExpect(status().isNotFound());
    }

    @Test
    void baseUrlsAreRestrictedToPlainHttpAndHttps() throws Exception {
        for (String bad : new String[] {"file:///etc/passwd", "javascript:alert(1)", "ftp://x.example.test", "https://user:pw@x.example.test", "not a url"}) {
            api.post(ADMIN, "/api/v1/admin/provider-instances", "{\"kind\":\"GIT_HOST\",\"adapterType\":\"GITLAB\",\"displayName\":\"bad " + suffix + "\",\"baseUrl\":\"" + bad + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BASE_URL"));
        }
        api.post(ADMIN, "/api/v1/admin/provider-instances", "{\"kind\":\"GIT_HOST\",\"adapterType\":\"NOPE\",\"displayName\":\"x\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_ADAPTER_TYPE"));
    }

    @Test
    void removingAnInstanceThatUsersHaveConnectionsFordisablesItInstead() throws Exception {
        String user = "holder-" + suffix + "@example.test";
        String id = Api.json(api.post(ADMIN, "/api/v1/admin/provider-instances", "{\"kind\":\"GIT_HOST\",\"adapterType\":\"GITLAB\",\"displayName\":\"Self-hosted GitLab " + suffix + "\",\"baseUrl\":\"https://git.corp.example.test\"}")
            .andExpect(status().isCreated()), "$.id");
        api.post(user, "/api/v1/me/connections", "{\"kind\":\"GIT_HOST\",\"providerType\":\"GITLAB\",\"authMethod\":\"PAT\",\"label\":\"pat\",\"secret\":\"glpat-xxxxxxxxxxxxxxxxxxxx\"}").andExpect(status().isCreated());
        api.delete(ADMIN, "/api/v1/admin/provider-instances/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(false)).andExpect(jsonPath("$.disabled").value(true));
        api.get(ADMIN, "/api/v1/admin/provider-instances?kind=GIT_HOST").andExpect(jsonPath("$[?(@.id=='" + id + "')].enabled").value(false));
    }
}
