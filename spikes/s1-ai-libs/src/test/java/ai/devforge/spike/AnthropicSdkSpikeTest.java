package ai.devforge.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicSdkSpikeTest {
    private static MessageCreateParams params() {
        var schema = Tool.InputSchema.builder()
            .properties(JsonValue.from(Map.of("pattern", Map.of("type", "string")))).build();
        return MessageCreateParams.builder().model("m").maxTokens(100).addUserMessage("find foo")
            .addTool(Tool.builder().name("repo_grep").inputSchema(schema).build()).build();
    }

    @Test
    void perClientAndPerRequestKeyOverride() throws Exception {
        try (var fake = new FakeAnthropic()) {
            AnthropicClient base = AnthropicOkHttpClient.builder().apiKey("user-A-key").baseUrl(fake.url()).build();
            Message m = base.messages().create(params());
            assertThat(m.content().get(0).isToolUse()).isTrue();
            assertThat(m.usage().inputTokens()).isEqualTo(21);
            // no public per-request key override: one client per credential (cacheable by credential id+version)
            AnthropicClient other = AnthropicOkHttpClient.builder().apiKey("user-B-key").baseUrl(fake.url()).build();
            other.messages().create(params());
            assertThat(fake.apiKeys).containsExactly("user-A-key", "user-B-key");
        }
    }
}
