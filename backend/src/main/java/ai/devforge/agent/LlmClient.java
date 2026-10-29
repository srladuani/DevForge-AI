package ai.devforge.agent;

/**
 * The only way the platform talks to a model. One adapter per provider (see ADR-0004/0013); the agent loop, policy and persistence
 * live outside it. Implementations must map vendor failures to {@link LlmException} and never log or retain the credential.
 */
public interface LlmClient {
    LlmResponse complete(LlmRequest request, CredentialContext credential);
}
