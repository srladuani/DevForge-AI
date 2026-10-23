package ai.devforge.approval;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** @param summary JSON describing exactly what is being approved (files, commands, risks) so the approver sees what the hash covers */
public record ApprovalView(UUID id, UUID workspaceId, UUID runId, ApprovalKind kind, String artifactHash, String summary, ApprovalStatus status, UUID requestedBy,
                           @Nullable UUID decidedBy, @Nullable Instant decidedAt, @Nullable String comment, Instant expiresAt, Instant createdAt) {}
