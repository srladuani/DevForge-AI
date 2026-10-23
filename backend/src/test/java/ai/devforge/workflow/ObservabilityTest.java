package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"devforge.workflow.queue-name=observability-test", "management.prometheus.metrics.export.enabled=true"})
class ObservabilityTest extends WorkflowTestBase {
    private static final String ADMIN = "admin@example.test";

    @Test
    void operatorsCanScrapeRunStepAndTokenMetricsAndNobodyElseCan() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        api.get(ADMIN, "/api/v1/workspaces").andExpect(status().isOk());

        String text = api.get(ADMIN, "/actuator/prometheus").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(text).contains("devforge_runs_started_total{").contains("devforge_step_duration_seconds_count{").contains("step=\"INVESTIGATION\"")
            .contains("devforge_model_tokens_total{").contains("devforge_sandbox_available").contains("devforge_jobs_queued");
        assertThat(text).as("metrics carry no user, run, ticket or credential data").doesNotContain(u("owner")).doesNotContain(run).doesNotContain("sim-key");

        api.get(u("owner"), "/actuator/prometheus").andExpect(status().isForbidden());
        api.get(u("owner"), "/actuator/metrics").andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health")).andExpect(status().isOk());
    }
}
