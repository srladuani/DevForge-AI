package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Walks every operation the API documents and checks the access rules from the outside: nobody unauthenticated gets in; a signed-in person with
 * no membership in the project never gets data or a success for something that belongs to it; a non-admin never reaches admin endpoints; and
 * nothing answers with a server error to hostile input. New endpoints are covered automatically because the list comes from the OpenAPI document.
 */
@TestPropertySource(properties = "devforge.workflow.queue-name=authz-matrix")
class ApiAuthorizationMatrixTest extends WorkflowTestBase {
    /** Endpoints that are about the caller themself, not about a project: a success for a stranger is correct there. */
    private static final Set<String> SELF_SCOPED = Set.of("/api/v1/project-types", "/api/v1/workspaces", "/api/v1/adapter-types", "/api/v1/agent-providers", "/api/v1/me/connections", "/api/v1/repository-sources");

    private record Op(HttpMethod method, String template) {}

    private List<Op> operations() throws Exception {
        String doc = mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        Map<String, Map<String, Object>> paths = JsonPath.read(doc, "$.paths");
        List<Op> ops = new ArrayList<>();
        paths.forEach((path, methods) -> methods.keySet().stream().filter(m -> Set.of("get", "post", "put", "patch", "delete").contains(m)).forEach(m -> ops.add(new Op(HttpMethod.valueOf(m.toUpperCase()), path))));
        return ops;
    }

    private String fill(String template, String run) {
        String p = template;
        if (p.startsWith("/api/v1/runs/")) p = p.replace("{id}", run);
        else if (p.startsWith("/api/v1/workspaces/")) p = p.replace("{id}", ws).replace("{workspaceId}", ws);
        return p.replace("{id}", "00000000-0000-4000-8000-000000000001").replace("{workspaceId}", ws).replace("{runId}", run).replace("{repoId}", backendId).replace("{userId}", "00000000-0000-4000-8000-000000000002")
            .replace("{changeId}", "00000000-0000-4000-8000-000000000003").replace("{key}", "ABC-1").replace("{action}", "cancel").replace("{providerType}", "GITHUB"); // an unsupported type answers 422 at once: the walk must never start a real sign-in with an outside service
    }

    private MockHttpServletRequestBuilder request(Op op, String uri, String user) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(op.method(), uri);
        if (user != null) b.header("X-Devforge-User", user);
        if (op.method() != HttpMethod.GET && op.method() != HttpMethod.DELETE) b.contentType(MediaType.APPLICATION_JSON).content("{}");
        return b;
    }

    @Test
    void everyDocumentedOperationEnforcesAuthenticationMembershipAndAdminRights() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        List<Op> ops = operations().stream().filter(o -> o.template().startsWith("/api/v1/")).toList();
        assertThat(ops.size()).as("the document lists the API").isGreaterThan(40);
        List<String> problems = new ArrayList<>();
        for (Op op : ops) {
            String uri = fill(op.template(), run);
            String name = op.method() + " " + op.template();
            int anonymous = mvc.perform(request(op, uri, null)).andReturn().getResponse().getStatus();
            // how to sign in is needed before anyone is signed in and holds nothing secret; the OAuth callback is a browser redirect that carries no login (it trusts only a single-use state)
            boolean publicByDesign = op.template().equals("/api/v1/auth/config") || op.template().equals("/api/v1/oauth/callback");
            if (anonymous != 401 && !publicByDesign) problems.add(name + ": unauthenticated got " + anonymous);
            int stranger = mvc.perform(request(op, uri, u("outsider"))).andReturn().getResponse().getStatus();
            if (stranger >= 500) problems.add(name + ": a stranger got a server error " + stranger);
            boolean selfScoped = publicByDesign || SELF_SCOPED.stream().anyMatch(s -> op.template().equals(s) || op.template().startsWith(s + "/"));
            if (!selfScoped && stranger >= 200 && stranger < 300) problems.add(name + ": a stranger got " + stranger);
            if (op.template().startsWith("/api/v1/admin/")) {
                int nonAdmin = mvc.perform(request(op, uri, u("owner"))).andReturn().getResponse().getStatus();
                if (nonAdmin != 403) problems.add(name + ": a non-admin got " + nonAdmin);
            }
        }
        assertThat(problems).as("authorization problems found by walking the API").isEmpty();
    }

    @Test
    void aViewerCanReadARunButNoMutatingActionIsAvailableToThem() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        List<String> problems = new ArrayList<>();
        for (Op op : operations()) {
            if (!op.template().startsWith("/api/v1/runs/") || op.method() == HttpMethod.GET) continue;
            int status = mvc.perform(request(op, fill(op.template(), run), u("viewer"))).andReturn().getResponse().getStatus();
            if (status < 400 || status >= 500) problems.add(op.method() + " " + op.template() + ": a viewer got " + status);
        }
        assertThat(problems).isEmpty();
        assertThat(mvc.perform(request(new Op(HttpMethod.GET, "/api/v1/runs/{id}"), "/api/v1/runs/" + run, u("viewer"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
