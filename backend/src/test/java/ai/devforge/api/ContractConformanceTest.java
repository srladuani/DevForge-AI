package ai.devforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.workflow.WorkflowTestBase;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real responses from real runs must satisfy the published OpenAPI schemas: every field the contract calls required is present, and no
 * field appears that the contract does not describe. This keeps the generated TypeScript client truthful.
 */
class ContractConformanceTest extends WorkflowTestBase {
    private Map<String, Object> schemas;
    private final List<String> problems = new ArrayList<>();

    @Test
    void responsesFromRealRunsConformToTheirSchemas() throws Exception {
        String doc = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        schemas = JsonPath.read(doc, "$.components.schemas");
        // The check below is only meaningful if the contract actually distinguishes required from optional fields.
        assertThat(JsonPath.<List<String>>read(doc, "$.components.schemas.RunView.required")).contains("id", "status", "mode", "agent", "nextActions", "repositories")
            .doesNotContain("notes", "failure", "awaitReason", "costUsd");
        assertThat(JsonPath.<List<String>>read(doc, "$.components.schemas.CreateRunRequest.required")).contains("mode", "input", "repositoryIds", "agent").doesNotContain("notes");
        assertThat(JsonPath.<List<String>>read(doc, "$.components.schemas.ConnectionView.required")).doesNotContain("lastVerifiedAt", "statusMessage");

        String done = createRun("FIX_AND_PATCH", TICKET_TEXT);
        awaitStatus(done, "AWAITING_APPROVAL");
        decide(u("approver"), pendingApprovalId(done), "APPROVE", pendingApprovalHash(done)).andExpect(status().isOk());
        awaitStatus(done, "AWAITING_REVIEW");
        String failed = createRun("INVESTIGATE_ONLY", TICKET_TEXT + "\n[sim:investigate=timeout]");
        awaitStatus(failed, "FAILED");
        String waiting = createRun("INVESTIGATE_ONLY", "export broken");
        awaitStatus(waiting, "AWAITING_INPUT");
        String ticket = Api("{\"mode\":\"INVESTIGATE_ONLY\",\"input\":{\"kind\":\"TICKET\",\"ref\":\"ABC-12\",\"text\":" + quote(TICKET_TEXT) + "},\"repositoryIds\":[\"" + backendId + "\"],\"agent\":" + agent() + "}");
        awaitStatus(ticket, "FINDINGS_AVAILABLE");

        for (String run : new String[] {done, failed, waiting, ticket}) {
            check("RunView", "/api/v1/runs/" + run);
            check("StepView", "/api/v1/runs/" + run + "/steps");
            check("FindingView", "/api/v1/runs/" + run + "/findings");
            check("ArtifactView", "/api/v1/runs/" + run + "/artifacts");
            check("ChangeView", "/api/v1/runs/" + run + "/changes");
            check("ValidationView", "/api/v1/runs/" + run + "/validations");
            check("EventView", "/api/v1/runs/" + run + "/events");
        }
        check("ApprovalView", "/api/v1/workspaces/" + ws + "/approvals");
        check("WorkspaceView", "/api/v1/workspaces");
        check("WorkspaceView", "/api/v1/workspaces/" + ws);
        check("Member", "/api/v1/workspaces/" + ws + "/members");
        check("WorkspaceRepoView", "/api/v1/workspaces/" + ws + "/repositories");
        check("ProjectTypeDefinition", "/api/v1/project-types");
        check("ConnectionView", "/api/v1/me/connections");
        check("ProviderOption", "/api/v1/agent-providers?workspaceId=" + ws);
        check("AdapterType", "/api/v1/adapter-types");
        check("AuditEventView", "/api/v1/admin/audit", "admin@example.test");
        check("ProviderInstanceView", "/api/v1/admin/provider-instances", "admin@example.test");
        assertThat(problems).as("contract violations").isEmpty();
    }

    private String Api(String body) throws Exception {
        return JsonPath.read(rawCreate(u("owner"), body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void check(String schema, String url) throws Exception { check(schema, url, u("owner")); }

    private void check(String schema, String url, String user) throws Exception {
        String body = api.get(user, url).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object json = JsonPath.parse(body).read("$");
        walk(schema, json, url);
    }

    @SuppressWarnings("unchecked")
    private void walk(String schemaName, Object json, String where) {
        if (json instanceof List<?> list) {
            list.forEach(e -> walk(schemaName, e, where));
            return;
        }
        Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaName);
        if (schema == null || !(json instanceof Map<?, ?> obj)) return;
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        for (String r : required) if (!obj.containsKey(r)) problems.add(where + ": " + schemaName + " is missing required field '" + r + "'");
        for (Object key : obj.keySet()) if (!props.containsKey(key)) problems.add(where + ": " + schemaName + " has undocumented field '" + key + "'");
        for (Map.Entry<String, Object> p : props.entrySet()) {
            Object value = obj.get(p.getKey());
            if (value == null) continue;
            Map<String, Object> def = (Map<String, Object>) p.getValue();
            String ref = refName(def);
            if (ref == null && def.get("items") instanceof Map<?, ?> items) ref = refName((Map<String, Object>) items);
            if (ref != null) walk(ref, value, where + "." + p.getKey());
        }
    }

    private static String refName(Map<String, Object> def) {
        Object ref = def.get("$ref");
        return ref instanceof String s ? s.substring(s.lastIndexOf('/') + 1) : null;
    }
}
