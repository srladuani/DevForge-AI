package ai.devforge.workflow;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import ai.devforge.testsupport.Api;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** On a shared server nobody may point DevForge at a path: every repository comes from a URL and the caller's own connection. */
@TestPropertySource(properties = "devforge.repositories.allow-local-paths=false")
class LocalPathsDisabledTest extends IntegrationTest {
    @Test
    void aPathIsRefusedWhenTheServerOnlyAcceptsUrls() throws Exception {
        Api api = new Api(mvc);
        String user = "paths-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String ws = Api.json(api.post(user, "/api/v1/workspaces", "{\"name\":\"p\",\"kind\":\"SUPPORT\"}").andExpect(status().isCreated()), "$.id");
        api.post(user, "/api/v1/workspaces/" + ws + "/repositories", "{\"path\":\"" + System.getProperty("java.io.tmpdir") + "\",\"role\":\"BACKEND\"}")
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_PATHS_DISABLED"));
    }
}
