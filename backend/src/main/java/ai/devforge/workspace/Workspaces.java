package ai.devforge.workspace;

import java.util.List;
import java.util.UUID;

/** Project data for other modules (no authorization: callers must use {@link WorkspaceAccess} first). */
public interface Workspaces {
    WorkspaceView get(UUID workspaceId, UUID asUserId);

    WorkspacePolicy policy(UUID workspaceId);

    WorkspaceKind kind(UUID workspaceId);

    List<WorkspaceRepoView> repositories(UUID workspaceId);

    java.util.Optional<TrackerBinding> tracker(UUID workspaceId);

    /** How this project's changes are published (defaults if never set). */
    PublishSettings publishSettings(UUID workspaceId);
}
