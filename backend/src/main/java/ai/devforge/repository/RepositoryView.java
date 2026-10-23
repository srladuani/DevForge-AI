package ai.devforge.repository;

import java.util.UUID;

public record RepositoryView(UUID id, String name, String localPath, String defaultBranch, String headSha, RepoProfile profile) {}
