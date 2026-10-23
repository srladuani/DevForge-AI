package ai.devforge.approval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Approvals {
    /** Opens a pending approval; any earlier pending approval of the same kind for the run is superseded. */
    ApprovalView request(UUID workspaceId, UUID runId, ApprovalKind kind, String artifactHash, String summaryJson, UUID requestedBy);

    /** Marks pending approvals of this run as superseded (the thing they covered changed or the run ended). */
    void supersedePending(UUID runId, String reason);

    Optional<ApprovalView> find(UUID approvalId);

    List<ApprovalView> forRun(UUID runId);

    /** Is there an approved, unexpired approval of this kind for exactly this artifact hash? Used as a hard gate before consequential steps. */
    boolean hasValidApproval(UUID runId, ApprovalKind kind, String artifactHash);
}
