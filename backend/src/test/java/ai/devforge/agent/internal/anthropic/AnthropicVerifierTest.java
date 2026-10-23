package ai.devforge.agent.internal.anthropic;

import static ai.devforge.testsupport.ScriptedAnthropicServer.failure;
import static org.assertj.core.api.Assertions.assertThat;

import ai.devforge.agent.internal.LiveModels;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.testsupport.ScriptedAnthropicServer;
import ai.devforge.testsupport.ScriptedAnthropicServer.Reply;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnthropicVerifierTest {
    private final ScriptedAnthropicServer server = new ScriptedAnthropicServer();
    private final LiveModels live = new LiveModels();
    private final AnthropicConnectionVerifier verifier = new AnthropicConnectionVerifier(new AnthropicEndpoint(AnthropicAdapterTest.providersPointingAt(server)), live);

    @AfterEach
    void stop() { server.close(); }

    @Test
    void aWorkingKeyIsVerifiedWithOneReadOnlyCallAndItsModelListIsRemembered() {
        UUID conn = UUID.randomUUID();
        var result = verifier.verify(conn, "sk-good-key");
        assertThat(result.status()).isEqualTo(ConnectionStatus.VALID);
        assertThat(result.message()).contains("3 model(s)").doesNotContain("sk-good-key");
        assertThat(live.forConnection(conn)).contains(Set.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"));
        assertThat(server.requests()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.path()).isEqualTo("/v1/models");
            assertThat(r.apiKey()).isEqualTo("sk-good-key");
        });
    }

    @Test
    void failuresMapToTheHonestStatusAndNeverClaimValid() {
        server.modelsReply(failure(401, "authentication_error", "invalid"));
        assertThat(verifier.verify(UUID.randomUUID(), "sk-bad").status()).isEqualTo(ConnectionStatus.INVALID);
        server.modelsReply(failure(429, "rate_limit_error", "slow"));
        assertThat(verifier.verify(UUID.randomUUID(), "sk-x").status()).isEqualTo(ConnectionStatus.QUOTA);
        server.modelsReply(failure(503, "api_error", "down"));
        assertThat(verifier.verify(UUID.randomUUID(), "sk-x").status()).as("an outage says nothing about the key").isEqualTo(ConnectionStatus.UNVERIFIED);
        server.modelsReply(new Reply(404, "{}", 0));
        assertThat(verifier.verify(UUID.randomUUID(), "sk-x").status()).isEqualTo(ConnectionStatus.INVALID);
    }

    @Test
    void anUnreachableProviderLeavesTheKeyUnverifiedWithAUsefulMessage() {
        server.close();
        var r = verifier.verify(UUID.randomUUID(), "sk-x");
        assertThat(r.status()).isEqualTo(ConnectionStatus.UNVERIFIED);
        assertThat(r.message()).contains("Could not reach");
    }

    @Test
    void aFailedVerificationDoesNotKeepAnOldModelList() {
        UUID conn = UUID.randomUUID();
        verifier.verify(conn, "sk-good");
        assertThat(live.forConnection(conn)).isPresent();
        server.modelsReply(failure(401, "authentication_error", "revoked"));
        assertThat(verifier.verify(conn, "sk-good").status()).isEqualTo(ConnectionStatus.INVALID);
    }
}
