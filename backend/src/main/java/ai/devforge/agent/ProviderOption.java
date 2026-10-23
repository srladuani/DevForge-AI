package ai.devforge.agent;

import ai.devforge.integration.AdapterAvailability;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One provider as offered to a specific user in a specific project: can they pick it, and what models would they see. */
public record ProviderOption(UUID instanceId, String provider, String displayName, AdapterAvailability availability, boolean hasUsableConnection,
                             boolean allowedByProject, boolean selectable, @Nullable String unavailableReason, List<ModelInfo> models) {}
