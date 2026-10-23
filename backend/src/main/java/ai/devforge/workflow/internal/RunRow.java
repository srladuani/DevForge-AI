package ai.devforge.workflow.internal;

import ai.devforge.workflow.RunMode;
import ai.devforge.workflow.RunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record RunRow(UUID id, UUID workspaceId, String type, RunMode mode, RunStatus status, UUID ownerUserId, String title, String notes, String idempotencyKey, String requestHash,
              String agentProvider, UUID agentInstanceId, String agentModel, String agentFastModel, UUID connectionId, boolean simulated, String awaitReason, String resumeState,
              String failureCode, String failureStep, String failureMessage, Boolean failureRetryable, boolean cancelRequested, boolean incomplete, long inputTokens,
              long outputTokens, BigDecimal costUsd, Instant createdAt, Instant updatedAt, long version) {}
