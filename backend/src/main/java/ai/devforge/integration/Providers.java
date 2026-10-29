package ai.devforge.integration;

import ai.devforge.identity.ConnectionKind;
import java.util.List;
import java.util.Optional;

public interface Providers {
    List<AdapterType> adapterTypes();

    List<ProviderInstanceView> instances(ConnectionKind kind, boolean enabledOnly);

    Optional<ProviderInstanceView> findEnabled(ConnectionKind kind, String adapterType);
}
