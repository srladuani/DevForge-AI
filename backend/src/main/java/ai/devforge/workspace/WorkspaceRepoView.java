package ai.devforge.workspace;

import ai.devforge.repository.RepoProfile;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record WorkspaceRepoView(UUID repositoryId, String name, RepoRole role, Map<String, Object> hints, String localPath, @Nullable String defaultBranch,
                                @Nullable String headSha, @Nullable RepoProfile profile) {}
