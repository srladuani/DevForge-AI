package ai.devforge.repository;

import java.util.Optional;
import java.util.UUID;

public interface Repositories {
    /**
     * Registers a local git repository (idempotent per path) and computes its profile.
     * The path must be inside an allowed root and be a git repository; it is only ever read.
     */
    RepositoryView registerLocal(String path, String nameOrNull);

    Optional<RepositoryView> find(UUID id);

    RepositoryView get(UUID id);

    /** The URL of the checkout's {@code origin} remote, if it has one. Read-only; used to find where a change would be published. */
    Optional<String> remoteUrl(UUID id);

    /** Re-reads HEAD and recomputes the profile. */
    RepositoryView refresh(UUID id);
}
