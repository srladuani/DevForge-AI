package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.identity.ConnectionVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * M1 SIMULATION. No key is ever sent to a provider. It only checks that the secret looks plausible so the rest of the flow can be
 * demonstrated; real verifiers (a minimal authenticated call per provider) replace it as adapters are built.
 */
@Component
@ConditionalOnProperty(name = "devforge.agent.mode", havingValue = "simulated", matchIfMissing = true)
class SimulatedConnectionVerifier implements ConnectionVerifier {
    @Override
    public boolean supports(ConnectionKind kind, String providerType) {
        return kind == ConnectionKind.AI_PROVIDER && providerType.equals("ANTHROPIC");
    }

    @Override
    public Result verify(String secret) {
        return secret != null && secret.strip().length() >= 8
            ? new Result(ConnectionStatus.VALID, "SIMULATED check only: the key was not sent to the provider")
            : new Result(ConnectionStatus.INVALID, "SIMULATED check: the key is too short to be real");
    }
}
