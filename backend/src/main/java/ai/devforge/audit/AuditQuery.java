package ai.devforge.audit;

import java.util.List;

public interface AuditQuery {
    List<AuditEventView> recent(int limit, String action);
}
