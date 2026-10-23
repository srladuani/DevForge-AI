package ai.devforge.approval.internal;

import ai.devforge.approval.ApprovalDecided;
import ai.devforge.approval.ApprovalKind;
import ai.devforge.approval.ApprovalStatus;
import ai.devforge.approval.ApprovalView;
import ai.devforge.approval.Approvals;
import ai.devforge.audit.AuditLog;
import ai.devforge.identity.CurrentUser;
import ai.devforge.platform.DomainException;
import ai.devforge.workspace.WorkspaceAccess;
import ai.devforge.workspace.WorkspaceRole.Permission;
import ai.devforge.workspace.Workspaces;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ApprovalService implements Approvals {
    private static final String COLS = "id, workspace_id, run_id, kind, artifact_hash, summary, status, requested_by, decided_by, decided_at, comment, expires_at, created_at";
    static final Duration TTL = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final WorkspaceAccess access;
    private final Workspaces workspaces;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    ApprovalService(JdbcClient jdbc, WorkspaceAccess access, Workspaces workspaces, ApplicationEventPublisher events, AuditLog audit, Clock clock, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.access = access;
        this.workspaces = workspaces;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    @Override
    @Transactional
    public ApprovalView request(UUID workspaceId, UUID runId, ApprovalKind kind, String artifactHash, String summaryJson, UUID requestedBy) {
        supersedePending(runId, "replaced by a newer " + kind + " request");
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into approval (id, workspace_id, run_id, kind, artifact_hash, summary, requested_by, expires_at) values (?,?,?,?,?,?,?,?)")
            .params(id, workspaceId, runId, kind.name(), artifactHash, summaryJson, requestedBy, Timestamp.from(Instant.now(clock).plus(TTL))).update();
        audit.record(requestedBy.toString(), "APPROVAL_REQUESTED", "run", runId.toString(), kind + " " + artifactHash);
        return find(id).orElseThrow();
    }

    @Override
    @Transactional
    public void supersedePending(UUID runId, String reason) {
        int n = jdbc.sql("update approval set status = 'SUPERSEDED', comment = coalesce(comment, ?) where run_id = ? and status = 'PENDING'").params(reason, runId).update();
        if (n > 0) audit.record("system", "APPROVAL_SUPERSEDED", "run", runId.toString(), reason);
    }

    @Override
    public Optional<ApprovalView> find(UUID id) {
        return jdbc.sql("select " + COLS + " from approval where id = ?").param(id).query(ApprovalService::map).optional();
    }

    @Override
    public List<ApprovalView> forRun(UUID runId) {
        return jdbc.sql("select " + COLS + " from approval where run_id = ? order by created_at").param(runId).query(ApprovalService::map).list();
    }

    @Override
    public boolean hasValidApproval(UUID runId, ApprovalKind kind, String artifactHash) {
        return jdbc.sql("select count(*) from approval where run_id = ? and kind = ? and artifact_hash = ? and status = 'APPROVED' and expires_at > now()")
            .params(runId, kind.name(), artifactHash).query(Integer.class).single() > 0;
    }

    List<ApprovalView> forWorkspace(UUID workspaceId, UUID userId, UUID runIdOrNull, ApprovalStatus statusOrNull) {
        access.require(workspaceId, userId, Permission.VIEW);
        expireOverdue();
        return jdbc.sql("select " + COLS + " from approval where workspace_id = ? and (cast(? as uuid) is null or run_id = ?) and (cast(? as text) is null or status = ?) order by created_at desc limit 200")
            .params(workspaceId, runIdOrNull, runIdOrNull, statusOrNull == null ? null : statusOrNull.name(), statusOrNull == null ? null : statusOrNull.name()).query(ApprovalService::map).list();
    }

    /**
     * Records a human decision. The caller must echo the hash of what they were shown; if the artifact has since changed the decision
     * is refused, so an approval can never be applied to something the approver did not see.
     */
    ApprovalView decide(UUID approvalId, CurrentUser decider, boolean approve, String seenHash, String comment) {
        ApprovalView a = find(approvalId).orElseThrow(() -> DomainException.notFound("APPROVAL_NOT_FOUND", "Approval not found"));
        access.require(a.workspaceId(), decider.id(), Permission.APPROVE);
        // Committed on its own: an expiry must stick even though this call then fails with APPROVAL_NOT_PENDING.
        expireOverdue();
        return tx.execute(status -> decideInTransaction(approvalId, decider, approve, seenHash, comment));
    }

    private ApprovalView decideInTransaction(UUID approvalId, CurrentUser decider, boolean approve, String seenHash, String comment) {
        ApprovalView a = find(approvalId).orElseThrow(() -> DomainException.notFound("APPROVAL_NOT_FOUND", "Approval not found"));
        if (a.status() != ApprovalStatus.PENDING) throw DomainException.conflict("APPROVAL_NOT_PENDING", "This approval is " + a.status());
        if (!a.artifactHash().equals(seenHash)) throw DomainException.conflict("ARTIFACT_CHANGED", "What you reviewed is not what is awaiting approval; reload and review again");
        if (workspaces.policy(a.workspaceId()).requireDifferentApprover() && decider.id().equals(a.requestedBy())) {
            throw DomainException.forbidden("SELF_APPROVAL_NOT_ALLOWED", "This project requires someone other than the requester to decide");
        }
        ApprovalStatus status = approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        jdbc.sql("update approval set status = ?, decided_by = ?, decided_at = now(), comment = ? where id = ? and status = 'PENDING'")
            .params(status.name(), decider.id(), comment, approvalId).update();
        audit.record(decider.id().toString(), "APPROVAL_" + status, "run", a.runId().toString(), a.kind() + " " + a.artifactHash());
        events.publishEvent(new ApprovalDecided(a.id(), a.workspaceId(), a.runId(), a.kind(), approve, a.artifactHash(), decider.id(), comment));
        return find(approvalId).orElseThrow();
    }

    private void expireOverdue() {
        jdbc.sql("update approval set status = 'EXPIRED' where status = 'PENDING' and expires_at <= now()").update();
    }

    private static ApprovalView map(ResultSet rs, int i) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new ApprovalView(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getObject("run_id", UUID.class), ApprovalKind.valueOf(rs.getString("kind")),
            rs.getString("artifact_hash"), rs.getString("summary"), ApprovalStatus.valueOf(rs.getString("status")), rs.getObject("requested_by", UUID.class),
            rs.getObject("decided_by", UUID.class), decided == null ? null : decided.toInstant(), rs.getString("comment"), rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());
    }
}
