package ai.devforge.agent;

import java.util.List;
import java.util.UUID;

public interface ModelCatalog {
    /** Providers as this user sees them in this project (workspaceId may be null for the personal "my connections" view). */
    List<ProviderOption> optionsFor(UUID userId, UUID workspaceIdOrNull);

    /**
     * Validates a requested provider/model against admin config, project policy, the user's own connections and model capabilities.
     *
     * @throws ai.devforge.platform.DomainException (422) with a code the UI can act on
     */
    AgentSelection select(UUID userId, UUID workspaceId, AgentSelectionRequest request);
}
