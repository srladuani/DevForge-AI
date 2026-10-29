package ai.devforge.agent.internal;

import ai.devforge.agent.LlmClient;

/** An {@link LlmClient} for one provider type, so the right adapter is chosen from the run's recorded provider. */
public interface ProviderLlmClient extends LlmClient {
    String provider();
}
