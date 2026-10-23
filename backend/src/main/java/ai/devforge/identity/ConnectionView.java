package ai.devforge.identity;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A connection as shown to clients and other modules. Deliberately has no secret field. */
public record ConnectionView(UUID id, UUID userId, ConnectionKind kind, String providerType, AuthMethod authMethod, String label,
                             ConnectionStatus status, @Nullable String statusMessage, @Nullable Instant lastVerifiedAt, Instant createdAt) {}
