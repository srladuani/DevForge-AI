package ai.devforge.integration;

import java.util.UUID;
import java.util.function.Supplier;

/** Lends a user's own Git host credential to one publication. The secret is read lazily and never stored in this object. */
public record HostCredential(UUID connectionId, UUID ownerUserId, Supplier<String> secret) {
    @Override public String toString() { return "HostCredential[connectionId=" + connectionId + ", secret=***]"; }
}
