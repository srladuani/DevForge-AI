package ai.devforge.workspace;

import java.util.EnumSet;
import java.util.Set;

public enum WorkspaceRole {
    VIEWER(EnumSet.of(Permission.VIEW)),
    OPERATOR(EnumSet.of(Permission.VIEW, Permission.RUN)),
    APPROVER(EnumSet.of(Permission.VIEW, Permission.RUN, Permission.APPROVE)),
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    WorkspaceRole(Set<Permission> permissions) { this.permissions = permissions; }

    public boolean allows(Permission p) { return permissions.contains(p); }

    public enum Permission { VIEW, RUN, APPROVE, MANAGE }
}
