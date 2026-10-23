package ai.devforge.agent;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Everything an agent step may know about a run. Ticket/prompt text is untrusted data. {@code attempt} counts executions of this step;
 * long-running implementations must poll {@code cancelRequested} between tool calls.
 */
public record RunContext(UUID runId, UUID ownerUserId, AgentSelection agent, String inputText, List<String> keywords, List<RepoTarget> repos, int attempt,
                         BooleanSupplier cancelRequested, UsageRecorder usage) {
    public RunContext(UUID runId, UUID ownerUserId, AgentSelection agent, String inputText, List<String> keywords, List<RepoTarget> repos, int attempt, BooleanSupplier cancelRequested) {
        this(runId, ownerUserId, agent, inputText, keywords, repos, attempt, cancelRequested, (step, in, out) -> { });
    }
}
