package ai.devforge.agent;

import java.util.UUID;

/** SIMULATED means nothing was executed: it is never reported as a pass. */
public record ValidationDraft(UUID repositoryId, String repositoryName, String command, Status status, String summary) {
    public enum Status { PASSED, FAILED, NOT_RUN, SIMULATED }
}
