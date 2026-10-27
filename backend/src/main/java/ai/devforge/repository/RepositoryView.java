package ai.devforge.repository;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** @param remoteUrl the plain https address of a repository DevForge cloned itself; null for a checkout registered by path */
public record RepositoryView(UUID id, String name, String localPath, String defaultBranch, String headSha, RepoProfile profile, @Nullable String remoteUrl, boolean managed) {}
