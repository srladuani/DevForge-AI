package ai.devforge.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ErrorContractTest extends IntegrationTest {

    @Test
    void unauthenticatedCallIs401WithStableCode() throws Exception {
        mvc.perform(get("/api/v1/me/connections"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void validationErrorsAreProblemJsonWithFieldsAndTraceId() throws Exception {
        mvc.perform(as(post("/api/v1/me/connections"), "u1@example.test").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"AI_PROVIDER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors.secret").exists())
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void malformedJsonIs400NotAStackTrace() throws Exception {
        mvc.perform(as(post("/api/v1/me/connections"), "u1@example.test").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("HTTP_400"))
            .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void unknownRouteIs404WithCode() throws Exception {
        mvc.perform(as(get("/api/v1/nope"), "u1@example.test")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HTTP_404"));
    }

    @Test
    void requestIdIsEchoedAndGeneratedWhenAbsent() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "abc12345-test")).andExpect(header().string("X-Request-Id", "abc12345-test"));
        mvc.perform(get("/actuator/health")).andExpect(header().exists("X-Request-Id"));
    }
}
