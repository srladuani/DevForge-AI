package ai.devforge.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to a user's connections for other modules. */
public interface Connections {
    List<ConnectionView> listFor(UUID userId);

    /** The user's most recently verified-valid connection of this type, if any. */
    Optional<ConnectionView> findUsable(UUID userId, ConnectionKind kind, String providerType);

    /**
     * Decrypts a secret in memory for the owner's own work (a run). Never log or persist the result.
     *
     * @throws ai.devforge.platform.DomainException if the connection does not belong to {@code ownerUserId}
     */
    String reveal(UUID connectionId, UUID ownerUserId);
}
