package ai.devforge.audit;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record AuditEventView(long id, Instant ts, String actor, String action, @Nullable String targetType, @Nullable String targetId, @Nullable String details) {}
