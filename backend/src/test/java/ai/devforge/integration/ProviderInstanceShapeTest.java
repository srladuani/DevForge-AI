package ai.devforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devforge.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/** Every provider instance the admin list returns has the full model policy, even if its row was stored with the old column default. */
class ProviderInstanceShapeTest extends IntegrationTest {
    @Test
    void anOldRowWithAnEmptyPolicyStillListsWithAllPolicyFields() throws Exception {
        String name = "Legacy " + UUID.randomUUID();
        jdbc.sql("insert into provider_instance (id, kind, adapter_type, display_name, model_policy) values (?, 'GIT_HOST', 'GITHUB', ?, '{}')").params(UUID.randomUUID(), name).update();
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/workspaces"), "admin@example.test")).andExpect(MockMvcResultMatchers.status().isOk());
        String body = mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/provider-instances"), "admin@example.test"))
            .andExpect(MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        var policies = com.jayway.jsonpath.JsonPath.<java.util.List<java.util.Map<String, Object>>>read(body, "$[?(@.displayName=='" + name + "')].modelPolicy");
        assertThat(policies).hasSize(1);
        assertThat(policies.getFirst()).containsKeys("allow", "deny", "tierOverrides", "customModels");
    }
}
