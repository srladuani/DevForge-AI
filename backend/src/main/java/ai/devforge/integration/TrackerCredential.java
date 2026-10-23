package ai.devforge.integration;

import java.util.UUID;
import java.util.function.Supplier;

/** Lends a user's own tracker credential to one fetch. The secret is read lazily and never stored in this object. */
public record TrackerCredential(UUID connectionId, UUID ownerUserId, Supplier<String> secret) {
    @Override public String toString() { return "TrackerCredential[connectionId=" + connectionId + ", secret=***]"; }
}
