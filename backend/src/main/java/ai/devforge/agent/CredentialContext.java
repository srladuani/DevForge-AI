package ai.devforge.agent;

import java.util.UUID;
import java.util.function.Supplier;

/** Lends the run owner's secret to an adapter for one call. The secret is fetched lazily and never stored in this object. */
public record CredentialContext(UUID connectionId, UUID ownerUserId, Supplier<String> secret) {
    @Override public String toString() { return "CredentialContext[connectionId=" + connectionId + ", secret=***]"; }
}
