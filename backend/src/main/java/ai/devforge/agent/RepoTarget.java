package ai.devforge.agent;

import ai.devforge.workspace.RepoRole;
import java.util.UUID;

/** A repository a run may look at, pinned to a commit. {@code testCommand} comes from the deterministic profile (may be null). */
public record RepoTarget(UUID repositoryId, String name, RepoRole role, String sha, String testCommand) {}
