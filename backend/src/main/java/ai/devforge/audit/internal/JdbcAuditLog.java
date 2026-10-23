package ai.devforge.audit.internal;

import ai.devforge.audit.AuditLog;
import ai.devforge.platform.Redactor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcAuditLog implements AuditLog {
    private final JdbcClient jdbc;

    JdbcAuditLog(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(String actor, String action, String targetType, String targetId, String details) {
        jdbc.sql("insert into audit_event (actor, action, target_type, target_id, details) values (?, ?, ?, ?, ?)")
            .params(actor, action, targetType, targetId, Redactor.redact(details))
            .update();
    }
}
