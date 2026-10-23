package ai.devforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.devforge.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * API contract gate. The generated OpenAPI document must equal the committed snapshot (backend/openapi.json), so an API change is always a
 * deliberate, reviewed diff. The frontend's typed client is generated from the same file. To accept an intended change run:
 * {@code ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true} and review the diff.
 */
class OpenApiContractTest extends IntegrationTest {
    private static final Path SNAPSHOT = Path.of("openapi.json");

    @Test
    void generatedApiMatchesTheCommittedSnapshot() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonMapper pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        Object tree = pretty.readValue(raw, Object.class);
        String generated = pretty.writeValueAsString(sorted(tree)) + "\n";

        if (Boolean.getBoolean("openapi.update") || !Files.exists(SNAPSHOT)) {
            Files.writeString(SNAPSHOT, generated, StandardCharsets.UTF_8);
        }
        String committed = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);
        assertThat(generated).as("API changed: review and accept with -Dopenapi.update=true").isEqualTo(committed);
    }

    @Test
    void everyOperationIsDocumentedAndNoInternalPathLeaks() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> paths = JsonPath.read(raw, "$.paths");
        assertThat(paths.keySet()).contains("/api/v1/workspaces/{workspaceId}/runs", "/api/v1/runs/{id}/actions/{action}", "/api/v1/approvals/{id}/decision",
            "/api/v1/me/connections", "/api/v1/agent-providers", "/api/v1/project-types", "/api/v1/runs/{id}/events");
        assertThat(paths.keySet()).allMatch(p -> p.startsWith("/api/v1/"));
        List<String> schemas = List.copyOf(JsonPath.<Map<String, Object>>read(raw, "$.components.schemas").keySet());
        assertThat(schemas).as("secrets are never part of a response schema").noneMatch(s -> s.toLowerCase().contains("encrypted"));
        String connectionView = JsonPath.read(raw, "$.components.schemas.ConnectionView").toString();
        assertThat(connectionView).doesNotContain("secret");
    }

    @SuppressWarnings("unchecked")
    private static Object sorted(Object o) {
        if (o instanceof Map<?, ?> m) {
            var out = new java.util.TreeMap<String, Object>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, sorted(v)));
            return out;
        }
        if (o instanceof List<?> l) return l.stream().map(OpenApiContractTest::sorted).toList();
        return o;
    }
}
