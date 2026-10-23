package ai.devforge.agent.internal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The model ids a user's credential could actually see the last time it was verified. Held in memory only (it is derived data and is
 * rebuilt on the next Verify); never contains a secret.
 */
@Component
public class LiveModels {
    private final Map<UUID, Set<String>> byConnection = new ConcurrentHashMap<>();

    public void remember(UUID connectionId, Set<String> modelIds) { byConnection.put(connectionId, Set.copyOf(modelIds)); }

    public Optional<Set<String>> forConnection(UUID connectionId) { return Optional.ofNullable(byConnection.get(connectionId)); }
}
