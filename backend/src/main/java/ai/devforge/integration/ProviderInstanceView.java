package ai.devforge.integration;

import ai.devforge.identity.ConnectionKind;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ProviderInstanceView(UUID id, ConnectionKind kind, String adapterType, String displayName, @Nullable String baseUrl, boolean enabled,
                                   ModelPolicy modelPolicy, AdapterAvailability availability, Instant updatedAt) {}
