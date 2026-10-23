package ai.devforge.workspace;

import ai.devforge.workspace.WorkspaceRole.Permission;
import java.util.Optional;
import java.util.UUID;

/** Authorization for everything scoped to a project. Non-members get a 404 so project ids cannot be probed. */
public interface WorkspaceAccess {
    /** @return the caller's role; @throws ai.devforge.platform.DomainException 404 if not a member, 403 if the role lacks the permission */
    WorkspaceRole require(UUID workspaceId, UUID userId, Permission permission);

    Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId);
}
