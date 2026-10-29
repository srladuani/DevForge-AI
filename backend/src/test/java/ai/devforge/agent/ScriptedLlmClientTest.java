package ai.devforge.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScriptedLlmClientTest {
    private static LlmRequest request() {
        return new LlmRequest("m", List.of(LlmRequest.ChatMessage.user("hi")), null, null, 100, Duration.ofSeconds(5));
    }

    private final CredentialContext cred = new CredentialContext(UUID.randomUUID(), UUID.randomUUID(), () -> { throw new AssertionError("secret must not be read"); });

    @Test
    void replaysTheScriptInOrderAndRecordsRequestsWithoutTouchingTheSecret() {
        var client = new ScriptedLlmClient()
            .thenReturn(new LlmResponse(null, List.of(new ToolCall("1", "repo_grep", "{}")), new LlmResponse.Usage(10, 5), LlmResponse.FinishReason.TOOL_CALLS))
            .thenReturn(new LlmResponse("done", null, new LlmResponse.Usage(20, 3), LlmResponse.FinishReason.END_TURN));
        assertThat(client.complete(request(), cred).toolCalls()).hasSize(1);
        assertThat(client.complete(request(), cred).text()).isEqualTo("done");
        assertThat(client.requests()).hasSize(2);
        assertThatThrownBy(() -> client.complete(request(), cred)).hasMessageContaining("exhausted");
    }

    @Test
    void failuresCarryProviderNeutralKindsAndRetryability() {
        var client = new ScriptedLlmClient().thenFail(new LlmException(LlmException.Kind.AUTH_INVALID, "bad key")).thenFail(new LlmException(LlmException.Kind.RATE_LIMITED, "slow down"));
        assertThatThrownBy(() -> client.complete(request(), cred)).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isFalse());
        assertThatThrownBy(() -> client.complete(request(), cred)).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
        assertThat(LlmException.Kind.QUOTA_EXHAUSTED.retryable()).isFalse();
        assertThat(LlmException.Kind.TIMEOUT.retryable()).isTrue();
    }

    @Test
    void credentialContextNeverPrintsTheSecret() {
        var c = new CredentialContext(UUID.randomUUID(), UUID.randomUUID(), () -> "sk-top-secret");
        assertThat(c.toString()).doesNotContain("sk-top-secret").contains("***");
    }
}
