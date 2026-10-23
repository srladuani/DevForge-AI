package ai.devforge.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

class SpringAiSpikeTest {
    record Grep(String pattern) {}

    private static ToolCallback tool() {
        Function<Grep, String> fn = g -> { throw new IllegalStateException("must not auto-execute"); };
        return FunctionToolCallback.builder("repo_grep", fn).description("grep").inputType(Grep.class).build();
    }

    @Test
    void perModelKeyAndPerPromptKeyWithManualToolLoop() throws Exception {
        try (var fake = new FakeAnthropic()) {
            var optsA = AnthropicChatOptions.builder().model("m").maxTokens(100).apiKey("user-A-key")
                .baseUrl(fake.url()).toolCallbacks(tool()).build();
            AnthropicChatModel model = AnthropicChatModel.builder().options(optsA).build();
            ChatResponse r = model.call(new Prompt("find foo"));
            assertThat(r.hasToolCalls()).isTrue();
            assertThat(r.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("repo_grep");
            assertThat(r.getMetadata().getUsage().getPromptTokens()).isEqualTo(21);
            // per-request override of the key on the same model instance
            var optsB = AnthropicChatOptions.builder().model("m").maxTokens(100).apiKey("user-B-key")
                .baseUrl(fake.url()).toolCallbacks(tool()).build();
            model.call(new Prompt("find foo", optsB));
            System.out.println("SPRING_AI_KEYS_SEEN=" + fake.apiKeys);
            assertThat(fake.apiKeys.get(0)).isEqualTo("user-A-key");
        }
    }
}
