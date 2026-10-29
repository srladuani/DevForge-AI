package ai.devforge.integration;

import java.util.UUID;
import java.util.function.Supplier;

/** Lends a user's own tracker credential to one fetch. The secret is read lazily and never stored in this object. */
public record TrackerCredential(UUID connectionId, UUID ownerUserId, Supplier<String> secret, java.util.function.Consumer<String> persist) {
    /** @param persist stores a replacement secret for this connection (an OAuth token that was refreshed) */
    public TrackerCredential(UUID connectionId, UUID ownerUserId, Supplier<String> secret) { this(connectionId, ownerUserId, secret, s -> { }); }

    @Override public String toString() { return "TrackerCredential[connectionId=" + connectionId + ", secret=***]"; }
}
