package ai.devforge.workspace;

import java.util.List;

/**
 * Human-authored limits for a project. Nothing a model produces can change these.
 *
 * @param allowedProviders         AI provider types users may pick in this project; empty means all configured ones
 * @param requireDifferentApprover whether the run owner may not approve their own external writes
 * @param maxRunCostUsd            soft budget per run
 * @param maxConcurrentRuns        active runs allowed at once in this project
 */
public record WorkspacePolicy(List<String> allowedProviders, boolean requireDifferentApprover, double maxRunCostUsd, int maxConcurrentRuns) {
    public static WorkspacePolicy defaults() { return new WorkspacePolicy(List.of(), false, 2.0, 2); }
}
