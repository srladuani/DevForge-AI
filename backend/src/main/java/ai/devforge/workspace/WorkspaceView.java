package ai.devforge.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceView(UUID id, String name, WorkspaceKind kind, WorkspacePolicy policy, Instant createdAt, WorkspaceRole yourRole) {}
