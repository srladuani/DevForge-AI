package ai.devforge.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.devforge.testsupport.Api;
import ai.devforge.workflow.WorkflowTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"devforge.workflow.queue-name=retention-test", "devforge.workflow.max-runs-per-user-per-day=3"})
class RunRetentionTest extends WorkflowTestBase {
    @Autowired RunRetention retention;

    private String finishedRun() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        act(u("owner"), run, "complete", null).andExpect(jsonPath("$.status").value("COMPLETED"));
        return run;
    }

    private int rows(String table, String run) { return jdbc.sql("select count(*) from " + table + (table.equals("run") ? " where id = ?" : " where run_id = ?")).param(UUID.fromString(run)).query(Integer.class).single(); }

    @Test
    void oldFinishedRunsAreDeletedWithEverythingTheyProducedAndRecentOrUnfinishedOnesAreKept() throws Exception {
        String old = finishedRun();
        String recent = finishedRun();
        String unfinished = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(unfinished, "FINDINGS_AVAILABLE");
        jdbc.sql("update run set updated_at = ? where id in (?, ?)").params(java.sql.Timestamp.from(Instant.now().minus(400, ChronoUnit.DAYS)), UUID.fromString(old), UUID.fromString(unfinished)).update();
        assertThat(rows("run_event", old)).isPositive();
        assertThat(rows("finding", old)).isPositive();

        assertThat(retention.purge(Instant.now().minus(365, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(rows("run", old)).isZero();
        for (String table : new String[] {"run_event", "run_input", "finding", "run_artifact", "run_step", "model_call"}) assertThat(rows(table, old)).as(table).isZero();
        assertThat(rows("run", recent)).isEqualTo(1);
        assertThat(rows("run", unfinished)).as("a run that is not finished is never purged").isEqualTo(1);
        api.get(u("owner"), "/api/v1/runs/" + old).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        assertThat(retention.purge(Instant.now().minus(365, ChronoUnit.DAYS))).isZero();
    }

    @Test
    void aPersonCannotStartMoreRunsInADayThanTheQuotaAllowsAndTheResponseSaysWhy() throws Exception {
        long before = jdbc.sql("select count(*) from run where owner_user_id = (select id from app_user where email = ?)").param(u("owner")).query(Long.class).single();
        for (long i = before; i < 3; i++) awaitStatus(createRun("INVESTIGATE_ONLY", TICKET_TEXT), "FINDINGS_AVAILABLE");
        rawCreate(u("owner"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("daily limit")));
        assertThat(Api.json(api.get(u("operator"), "/api/v1/workspaces/" + ws), "$.id")).as("other people are unaffected").isEqualTo(ws);
        rawCreate(u("operator"), body("INVESTIGATE_ONLY", TICKET_TEXT)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
    }
}
