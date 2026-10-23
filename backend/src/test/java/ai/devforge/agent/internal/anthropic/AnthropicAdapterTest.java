package ai.devforge.agent.internal.anthropic;

import static ai.devforge.testsupport.ScriptedAnthropicServer.failure;
import static ai.devforge.testsupport.ScriptedAnthropicServer.slow;
import static ai.devforge.testsupport.ScriptedAnthropicServer.text;
import static ai.devforge.testsupport.ScriptedAnthropicServer.toolUse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devforge.agent.CredentialContext;
import ai.devforge.agent.LlmException;
import ai.devforge.agent.LlmException.Kind;
import ai.devforge.agent.LlmRequest;
import ai.devforge.agent.LlmRequest.ChatMessage;
import ai.devforge.agent.LlmRequest.ToolSpec;
import ai.devforge.agent.LlmResponse;
import ai.devforge.agent.ToolCall;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.integration.AdapterAvailability;
import ai.devforge.integration.ModelPolicy;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.integration.Providers;
import ai.devforge.integration.AdapterType;
import ai.devforge.testsupport.ScriptedAnthropicServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Contract tests for the Claude adapter against a scripted local server. They prove our side of the protocol, not the real service's behaviour. */
class AnthropicAdapterTest {
    private final ScriptedAnthropicServer server = new ScriptedAnthropicServer();
    private final AnthropicLlmClient client = new AnthropicLlmClient(new AnthropicEndpoint(providersPointingAt(server)));

    @AfterEach
    void stop() { server.close(); }

    static Providers providersPointingAt(ScriptedAnthropicServer s) {
        return new Providers() {
            @Override public List<AdapterType> adapterTypes() { return List.of(); }
            @Override public List<ProviderInstanceView> instances(ConnectionKind kind, boolean enabledOnly) { return List.of(); }
            @Override public Optional<ProviderInstanceView> findEnabled(ConnectionKind kind, String adapterType) {
                return Optional.of(new ProviderInstanceView(UUID.randomUUID(), kind, adapterType, "test", s.url(), true, ModelPolicy.none(), AdapterAvailability.REAL, Instant.now()));
            }
        };
    }

    private static CredentialContext key(String secret) { return new CredentialContext(UUID.randomUUID(), UUID.randomUUID(), () -> secret); }

    private static LlmRequest request(Duration timeout, ChatMessage... msgs) {
        ToolSpec grep = new ToolSpec("repo_grep", "grep", "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"term\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"},\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"repo\",\"term\"]}");
        return new LlmRequest("claude-sonnet-5-5", List.of(msgs), List.of(grep), null, 512, timeout);
    }

    @Test
    void aToolRequestComesBackUnexecutedWithUsageAndTheFullConversationIsSentInOrder() {
        server.then(toolUse("toolu_1", "repo_grep", "{\"repo\":\"api\",\"term\":\"video\"}", 21, 9));
        LlmResponse r = client.complete(request(Duration.ofSeconds(5), ChatMessage.system("be careful"), ChatMessage.user("find it"),
            ChatMessage.assistant("looking", List.of(new ToolCall("toolu_0", "repo_grep", "{\"repo\":\"api\",\"term\":\"x\"}"))),
            ChatMessage.toolResult("toolu_0", "repo_grep", "no match")), key("sk-secret-1"));

        assertThat(r.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("toolu_1");
            assertThat(c.name()).isEqualTo("repo_grep");
            assertThat(c.argumentsJson()).contains("\"term\"").contains("video");
        });
        assertThat(r.finishReason()).isEqualTo(LlmResponse.FinishReason.TOOL_CALLS);
        assertThat(r.usage()).isEqualTo(new LlmResponse.Usage(21, 9));

        String sent = server.messageRequests().getFirst().body();
        assertThat(sent).contains("be careful").contains("find it").contains("repo_grep").contains("tool_use").contains("tool_result").contains("no match");
        assertThat(sent.indexOf("find it")).isLessThan(sent.indexOf("tool_result"));
        assertThat(sent).contains("\"input_schema\"").contains("\"required\"").contains("\"tags\"");
    }

    @Test
    void aPlainAnswerHasNoToolCallsAndEndsTheTurn() {
        server.then(text("all done", 5, 3));
        LlmResponse r = client.complete(request(Duration.ofSeconds(5), ChatMessage.user("hi")), key("sk-secret-1"));
        assertThat(r.text()).isEqualTo("all done");
        assertThat(r.toolCalls()).isEmpty();
        assertThat(r.finishReason()).isEqualTo(LlmResponse.FinishReason.END_TURN);
    }

    @Test
    void everyCallUsesTheCredentialLentForThatCallAndNothingIsRetained() {
        server.then(text("a", 1, 1)).then(text("b", 1, 1));
        client.complete(request(Duration.ofSeconds(5), ChatMessage.user("hi")), key("sk-user-A"));
        client.complete(request(Duration.ofSeconds(5), ChatMessage.user("hi")), key("sk-user-B"));
        assertThat(server.messageRequests()).extracting(ScriptedAnthropicServer.Recorded::apiKey).containsExactly("sk-user-A", "sk-user-B");
    }

    @Test
    void vendorFailuresBecomeProviderNeutralKindsWithRetryabilityAndNeverEchoTheKeyOrPrompt() {
        record Case(ScriptedAnthropicServer.Reply reply, Kind kind) {}
        List<Case> cases = List.of(
            new Case(failure(401, "authentication_error", "invalid x-api-key sk-secret-1"), Kind.AUTH_INVALID),
            new Case(failure(403, "permission_error", "forbidden"), Kind.AUTH_INVALID),
            new Case(failure(429, "rate_limit_error", "slow down"), Kind.RATE_LIMITED),
            new Case(failure(500, "api_error", "boom"), Kind.UNAVAILABLE),
            new Case(failure(529, "overloaded_error", "overloaded"), Kind.UNAVAILABLE),
            new Case(failure(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API"), Kind.QUOTA_EXHAUSTED),
            new Case(failure(400, "invalid_request_error", "messages: text content blocks must be non-empty"), Kind.BAD_REQUEST));
        for (Case c : cases) {
            server.then(c.reply());
            assertThatThrownBy(() -> client.complete(request(Duration.ofSeconds(5), ChatMessage.user("my private prompt about the code")), key("sk-secret-1")))
                .as("status %d", c.reply().status())
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(c.kind());
                    assertThat(e.getMessage()).doesNotContain("sk-secret-1").doesNotContain("private prompt");
                });
        }
        assertThat(Kind.AUTH_INVALID.retryable()).isFalse();
        assertThat(Kind.QUOTA_EXHAUSTED.retryable()).isFalse();
        assertThat(Kind.RATE_LIMITED.retryable()).isTrue();
        assertThat(Kind.UNAVAILABLE.retryable()).isTrue();
    }

    @Test
    void theVendorLibraryDoesNotRetryOnItsOwn() {
        server.then(failure(429, "rate_limit_error", "slow down")).then(text("would have succeeded on a hidden retry", 1, 1));
        assertThatThrownBy(() -> client.complete(request(Duration.ofSeconds(5), ChatMessage.user("hi")), key("sk-secret-1")))
            .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        assertThat(server.messageRequests()).as("retries are the workflow's decision (visible, bounded, with backoff)").hasSize(1);
    }

    @Test
    void aSlowProviderTimesOutInsteadOfHangingTheWorker() {
        server.then(slow(text("too late", 1, 1), 2000));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.complete(request(Duration.ofMillis(300), ChatMessage.user("hi")), key("sk-secret-1")))
            .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1500).dividedBy(1000).plusMillis(500));
    }

    @Test
    void aProviderThatCannotBeReachedIsUnavailableNotACrash() {
        server.close();
        assertThatThrownBy(() -> client.complete(request(Duration.ofSeconds(2), ChatMessage.user("hi")), key("sk-secret-1")))
            .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isIn(Kind.UNAVAILABLE, Kind.TIMEOUT));
    }

    @Test
    void theSchemaConverterHandlesNestedObjectsArraysEnumsAndRequiredFields() {
        var schema = SchemaConverter.objectSchema("{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"]},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
            + "\"properties\":{\"n\":{\"type\":\"integer\"},\"ok\":{\"type\":\"boolean\"},\"x\":{\"type\":\"number\"}},\"required\":[\"n\"]}}},\"required\":[\"label\"]}");
        assertThat(schema.properties()).containsKeys("label", "items");
        assertThat(schema.required()).containsExactly("label");
        assertThatThrownBy(() -> SchemaConverter.objectSchema("{\"type\":\"string\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaConverter.objectSchema("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"weird\"}}}")).isInstanceOf(IllegalArgumentException.class);
    }
}
