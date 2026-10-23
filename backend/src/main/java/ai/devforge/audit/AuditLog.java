package ai.devforge.audit;

/** Append-only record of who did what. Details are redacted before storage; never pass secrets. */
public interface AuditLog {
    void record(String actor, String action, String targetType, String targetId, String details);

    default void record(String actor, String action) { record(actor, action, null, null, null); }
}
