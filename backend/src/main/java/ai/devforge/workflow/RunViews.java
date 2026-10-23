package ai.devforge.workflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models returned by the runs API. */
public final class RunViews {
    private RunViews() {}

    public record AgentView(String provider, String model, @Nullable String fastModel, boolean simulated) {}

    public record FailureView(String code, String step, String message, boolean retryable) {}

    public record RepoView(UUID repositoryId, String name, String role, String baseSha) {}

    public record InputView(UUID id, String kind, @Nullable String sourceRef, String text, @Nullable Map<String, Object> normalized, Instant createdAt) {}

    public record RunView(UUID id, UUID workspaceId, String type, RunMode mode, RunStatus status, UUID ownerUserId, String title, @Nullable String notes, AgentView agent,
                          @Nullable String awaitReason, @Nullable String resumeState, @Nullable FailureView failure, boolean cancelRequested, boolean incomplete, long inputTokens, long outputTokens,
                          @Nullable BigDecimal costUsd, Instant createdAt, Instant updatedAt, List<RepoView> repositories, List<InputView> inputs, List<String> nextActions) {}

    public record StepView(UUID id, String name, int attempt, String status, Instant startedAt, @Nullable Instant finishedAt, @Nullable String detail) {}

    public record EventView(int seq, String type, Map<String, Object> payload, Instant createdAt) {}

    public record EvidenceView(UUID id, UUID repositoryId, String repoName, String sha, String path, int startLine, int endLine, @Nullable String contentHash, @Nullable String excerpt,
                               boolean verified, @Nullable String problem) {}

    public record FindingView(UUID id, String step, String label, String requestedLabel, @Nullable String downgradeReason, String text, String confidence, List<EvidenceView> evidence) {}

    public record ArtifactView(UUID id, String type, Map<String, Object> content, String contentHash, boolean current, Instant createdAt) {}

    public record ValidationView(UUID id, UUID codeChangeId, String repoName, @Nullable String command, String status, String summary, int attempt) {}

    public record ChangeView(UUID id, UUID repositoryId, String repoName, String baseSha, String patch, String patchHash, List<String> files, boolean simulated, int attempt) {}

    /** A possible credential in the added lines of a patch. {@code BLOCK} findings stop the export; {@code WARN} ones are shown for the reviewer. The value itself is never included. */
    public record SecretFinding(String file, String rule, String severity) {}

    public record ExportRepoView(UUID changeId, String repoName, String branch, String commitSubject, List<String> files, String patchFileName, @Nullable String validationStatus,
                                 List<SecretFinding> secrets, boolean downloadable) {}

    /**
     * Everything needed to take a finished change out of DevForge without any Git host: a branch name, a {@code git am}-able patch per repository and a
     * traceable pull-request description. Nothing is pushed anywhere.
     */
    public record ExportView(UUID runId, String branchName, String prDescription, List<ExportRepoView> repositories, List<String> warnings, boolean blocked) {}
}
