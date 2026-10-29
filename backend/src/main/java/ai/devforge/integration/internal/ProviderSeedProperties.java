package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("devforge.providers")
record ProviderSeedProperties(@DefaultValue List<Seed> seed) {
    record Seed(ConnectionKind kind, String adapterType, String displayName, String baseUrl) {}
}
