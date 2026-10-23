package ai.devforge.approval;

import java.util.UUID;

/**
 * Published inside the deciding transaction. A listener that cannot honour the decision (e.g. the artifact changed) throws, which
 * rolls the decision back.
 */
public record ApprovalDecided(UUID approvalId, UUID workspaceId, UUID runId, ApprovalKind kind, boolean approved, String artifactHash, UUID decidedBy, String comment) {}
