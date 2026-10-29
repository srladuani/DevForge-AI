package ai.devforge.agent;

import java.util.List;
import java.util.UUID;

/** A unified diff for one repository, as text. Nothing has been applied anywhere. */
public record ChangeDraft(UUID repositoryId, String repositoryName, String patch, List<String> files, boolean simulated) {}
