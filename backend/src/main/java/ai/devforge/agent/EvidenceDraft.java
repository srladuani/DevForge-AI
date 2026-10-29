package ai.devforge.agent;

import java.util.UUID;

public record EvidenceDraft(UUID repositoryId, String path, int startLine, int endLine) {}
