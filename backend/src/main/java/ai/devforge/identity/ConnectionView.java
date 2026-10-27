package ai.devforge.identity;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A connection as shown to clients and other modules. Deliberately has no secret field.
 *
 * @param scope Git hosts only: null = usable for any repository on the host, {@code owner/name} = that repository, {@code owner/*} = all of an owner's repositories
 */
public record ConnectionView(UUID id, UUID userId, ConnectionKind kind, String providerType, AuthMethod authMethod, String label,
                             @Nullable String scope, ConnectionStatus status, @Nullable String statusMessage, @Nullable Instant lastVerifiedAt, Instant createdAt) {}
