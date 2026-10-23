package ai.devforge.agent.internal;

import ai.devforge.agent.LlmClient;
import ai.devforge.agent.LlmException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LlmClientRegistry {
    private final List<ProviderLlmClient> clients;

    public LlmClientRegistry(List<ProviderLlmClient> clients) { this.clients = clients; }

    public LlmClient forProvider(String provider) {
        return clients.stream().filter(c -> c.provider().equalsIgnoreCase(provider)).findFirst()
            .orElseThrow(() -> new LlmException(LlmException.Kind.BAD_REQUEST, "No adapter is available for provider " + provider));
    }
}
