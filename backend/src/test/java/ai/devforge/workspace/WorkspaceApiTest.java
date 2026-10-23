package ai.devforge.workspace;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.FixtureRepos;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceApiTest extends IntegrationTest {
    @TempDir Path tmp;
    Api api;
    final String suffix = UUID.randomUUID().toString().substring(0, 8);

    String u(String name) { return name + "-" + suffix + "@example.test"; }

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        // users must have signed in once before they can be added to a project
        for (String n : new String[] {"owner", "viewer", "operator", "outsider"}) api.get(u(n), "/api/v1/workspaces").andExpect(status().isOk());
    }

    String createProject(String owner) throws Exception {
        return Api.json(api.post(owner, "/api/v1/workspaces", "{\"name\":\"ProgressiveBeef support\",\"kind\":\"SUPPORT\"}")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.yourRole").value("ADMIN")).andExpect(jsonPath("$.policy.maxConcurrentRuns").value(2)), "$.id");
    }

    @Test
    void projectTypesShowSupportEnabledAndNewProjectComingSoon() throws Exception {
        api.get(u("owner"), "/api/v1/project-types").andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.kind=='SUPPORT')].enabled").value(true))
            .andExpect(jsonPath("$[?(@.kind=='NEW_PROJECT')].enabled").value(false))
            .andExpect(jsonPath("$[?(@.kind=='NEW_PROJECT')].disabledReason").value("Coming soon"));
    }

    @Test
    void newProjectTypeCannotBeCreatedYet() throws Exception {
        api.post(u("owner"), "/api/v1/workspaces", "{\"name\":\"x\",\"kind\":\"NEW_PROJECT\"}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PROJECT_TYPE_NOT_AVAILABLE"));
        api.post(u("owner"), "/api/v1/workspaces", "{\"name\":\"x\",\"kind\":\"WHATEVER\"}").andExpect(status().isBadRequest());
        api.post(u("owner"), "/api/v1/workspaces", "{\"name\":\"\",\"kind\":\"SUPPORT\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void aProjectHoldsABackendAndAFrontendRepositoryEachWithARole() throws Exception {
        String ws = createProject(u("owner"));
        Path backend = FixtureRepos.gradleSpring(tmp.resolve("backend"));
        Path web = FixtureRepos.reactWebpack(tmp.resolve("web"));
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories",
                "{\"path\":\"" + backend + "\",\"name\":\"api\",\"role\":\"BACKEND\",\"hints\":{\"exposes\":\"/api/v1\"}}")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("BACKEND"))
            .andExpect(jsonPath("$.profile.buildTools[0].tool").value("gradle")).andExpect(jsonPath("$.hints.exposes").value("/api/v1"));
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"path\":\"" + web + "\",\"name\":\"admin-portal\",\"role\":\"WEB_FRONTEND\"}")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.profile.buildTools[0].tool").value("yarn"));
        api.get(u("owner"), "/api/v1/workspaces/" + ws + "/repositories").andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.name=='api')].role").value("BACKEND")).andExpect(jsonPath("$[?(@.name=='admin-portal')].role").value("WEB_FRONTEND"));
    }

    @Test
    void repositoryRegistrationGuards() throws Exception {
        String ws = createProject(u("owner"));
        Path backend = FixtureRepos.mavenSpring(tmp.resolve("dup"));
        String body = "{\"path\":\"" + backend + "\",\"role\":\"BACKEND\"}";
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", body).andExpect(status().isCreated());
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPOSITORY_ALREADY_IN_PROJECT"));
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"cloneUrl\":\"https://example.test/a.git\",\"role\":\"BACKEND\"}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CLONE_NOT_AVAILABLE"));
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"path\":\"/usr\",\"role\":\"BACKEND\"}")
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REPOSITORY_PATH_NOT_ALLOWED"));
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"role\":\"BACKEND\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PATH_REQUIRED"));
        String huge = "x".repeat(5000);
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"path\":\"" + FixtureRepos.mavenSpring(tmp.resolve("big")) + "\",\"role\":\"LIBRARY\",\"hints\":{\"a\":\"" + huge + "\"}}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HINTS_TOO_LARGE"));
    }

    @Test
    void authorizationMatrixAcrossRoles() throws Exception {
        String ws = createProject(u("owner"));
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/members", "{\"email\":\"" + u("viewer") + "\",\"role\":\"VIEWER\"}").andExpect(status().isOk());
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/members", "{\"email\":\"" + u("operator") + "\",\"role\":\"OPERATOR\"}").andExpect(status().isOk());
        Path repo = FixtureRepos.mavenSpring(tmp.resolve("authz"));
        String add = "{\"path\":\"" + repo + "\",\"role\":\"BACKEND\"}";

        // non-member: project does not exist for them
        api.get(u("outsider"), "/api/v1/workspaces/" + ws).andExpect(status().isNotFound());
        api.get(u("outsider"), "/api/v1/workspaces/" + ws + "/repositories").andExpect(status().isNotFound());
        api.post(u("outsider"), "/api/v1/workspaces/" + ws + "/repositories", add).andExpect(status().isNotFound());
        api.get(u("outsider"), "/api/v1/workspaces").andExpect(jsonPath("$.length()").value(0));

        // viewer and operator can read but not manage
        for (String who : new String[] {"viewer", "operator"}) {
            api.get(u(who), "/api/v1/workspaces/" + ws).andExpect(status().isOk());
            api.post(u(who), "/api/v1/workspaces/" + ws + "/repositories", add).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
            api.patch(u(who), "/api/v1/workspaces/" + ws, "{\"name\":\"hijack\"}").andExpect(status().isForbidden());
            api.put(u(who), "/api/v1/workspaces/" + ws + "/members", "{\"email\":\"" + u("outsider") + "\",\"role\":\"ADMIN\"}").andExpect(status().isForbidden());
        }
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", add).andExpect(status().isCreated());
        api.get(u("viewer"), "/api/v1/workspaces").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].yourRole").value("VIEWER"));
    }

    @Test
    void membersMustHaveSignedInAndCannotLockThemselvesOut() throws Exception {
        String ws = createProject(u("owner"));
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/members", "{\"email\":\"never-seen-" + suffix + "@example.test\",\"role\":\"VIEWER\"}")
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        api.put(u("owner"), "/api/v1/workspaces/" + ws + "/members", "{\"email\":\"" + u("owner") + "\",\"role\":\"VIEWER\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_DEMOTE_SELF"));
    }

    @Test
    void policyIsValidatedAndEditableOnlyByAdmins() throws Exception {
        String ws = createProject(u("owner"));
        api.patch(u("owner"), "/api/v1/workspaces/" + ws, "{\"policy\":{\"allowedProviders\":[\"anthropic\"],\"requireDifferentApprover\":true,\"maxRunCostUsd\":5,\"maxConcurrentRuns\":3}}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.policy.allowedProviders[0]").value("ANTHROPIC")).andExpect(jsonPath("$.policy.requireDifferentApprover").value(true));
        api.patch(u("owner"), "/api/v1/workspaces/" + ws, "{\"policy\":{\"allowedProviders\":[],\"requireDifferentApprover\":false,\"maxRunCostUsd\":5,\"maxConcurrentRuns\":99}}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_POLICY"));
    }
}
