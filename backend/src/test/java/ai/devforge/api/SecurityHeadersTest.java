package ai.devforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import ai.devforge.IntegrationTest;
import org.junit.jupiter.api.Test;

class SecurityHeadersTest extends IntegrationTest {
    @Test
    void theApiSendsRestrictiveHeadersAndAllowsNoCrossOriginBrowserAccess() throws Exception {
        var res = mvc.perform(as(get("/api/v1/workspaces"), "headers@example.test")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("frame-ancestors 'none'");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(res.getHeader("Cache-Control")).contains("no-store");

        var cross = mvc.perform(as(get("/api/v1/workspaces"), "headers@example.test").header("Origin", "https://evil.example")).andReturn().getResponse();
        assertThat(cross.getHeader("Access-Control-Allow-Origin")).isNull();
        var preflight = mvc.perform(options("/api/v1/workspaces").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(preflight.getStatus()).isGreaterThanOrEqualTo(400);

        var error = mvc.perform(get("/api/v1/runs/not-a-uuid").header("X-Devforge-User", "headers@example.test")).andReturn().getResponse().getContentAsString();
        assertThat(error).doesNotContain("Exception").doesNotContain("at ai.devforge").doesNotContain("org.springframework");
    }
}
