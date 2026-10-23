package ai.devforge.audit.internal;

import ai.devforge.audit.AuditEventView;
import ai.devforge.audit.AuditQuery;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcAuditQuery implements AuditQuery {
    private final JdbcClient jdbc;

    JdbcAuditQuery(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public List<AuditEventView> recent(int limit, String action) {
        int capped = Math.max(1, Math.min(limit, 500));
        String where = action == null ? "" : "where action = :action ";
        var spec = jdbc.sql("select id, ts, actor, action, target_type, target_id, details from audit_event " + where + "order by id desc limit :limit")
            .param("limit", capped);
        if (action != null) spec = spec.param("action", action);
        return spec.query((rs, i) -> new AuditEventView(rs.getLong("id"), rs.getTimestamp("ts").toInstant(), rs.getString("actor"), rs.getString("action"),
            rs.getString("target_type"), rs.getString("target_id"), rs.getString("details"))).list();
    }
}
