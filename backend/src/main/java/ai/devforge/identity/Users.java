package ai.devforge.identity;

import java.util.Optional;
import java.util.UUID;

public interface Users {
    Optional<CurrentUser> find(UUID id);

    Optional<CurrentUser> findByEmail(String email);
}
