package ai.devforge.workflow.internal;

import ai.devforge.audit.AuditLog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes finished runs (completed, cancelled or failed) once they are older than the configured retention, together with everything they
 * produced: inputs, findings, patches, validations, events, approvals and publications. Off by default. The audit log is kept: it records that a
 * purge happened, not what the runs contained.
 */
@Component
class RunRetention {
    private static final Logger log = LoggerFactory.getLogger(RunRetention.class);
    private final JdbcClient jdbc;
    private final WorkflowProperties props;
    private final AuditLog audit;

    RunRetention(JdbcClient jdbc, WorkflowProperties props, AuditLog audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT6H")
    void scheduled() {
        if (props.retentionDays() > 0) purge(Instant.now().minus(Duration.ofDays(props.retentionDays())));
    }

    /** @return how many runs were deleted */
    int purge(Instant cutoff) {
        List<UUID> ids = jdbc.sql("select id from run where status in ('COMPLETED', 'CANCELLED', 'FAILED') and updated_at < ?").param(java.sql.Timestamp.from(cutoff)).query(UUID.class).list();
        if (ids.isEmpty()) return 0;
        for (UUID id : ids) {
            jdbc.sql("delete from approval where run_id = ?").param(id).update();
            jdbc.sql("delete from publication where run_id = ?").param(id).update();
            jdbc.sql("delete from run where id = ?").param(id).update();
        }
        audit.record("system", "RETENTION_PURGE", "run", String.valueOf(ids.size()), "older than " + cutoff);
        log.info("Retention removed {} finished run(s) older than {}", ids.size(), cutoff);
        return ids.size();
    }
}
