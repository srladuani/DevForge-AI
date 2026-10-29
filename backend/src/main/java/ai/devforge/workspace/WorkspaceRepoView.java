package ai.devforge.workspace;

import ai.devforge.repository.RepoProfile;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param remoteUrl the address DevForge cloned this repository from; null when it was registered by a path on the server
 * @param managed   DevForge keeps this clone current itself
 * @param targetBranch the branch pull requests for this repository go into; null = use the project's target branch, else the host's default
 */
public record WorkspaceRepoView(UUID repositoryId, String name, RepoRole role, Map<String, Object> hints, String localPath, @Nullable String defaultBranch,
                                @Nullable String headSha, @Nullable RepoProfile profile, @Nullable String targetBranch, @Nullable String remoteUrl, boolean managed) {}
