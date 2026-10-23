package ai.devforge.identity;

import java.util.UUID;

public record CurrentUser(UUID id, String email, String displayName, boolean admin) {}
