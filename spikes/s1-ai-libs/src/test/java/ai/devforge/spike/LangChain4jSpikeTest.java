package ai.devforge.spike;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

class LangChain4jSpikeTest {
    @Test
    void perCredentialModelsToolRequestReturnedUnexecutedAndUsageRead() throws Exception {
        try (var fake = new FakeAnthropic()) {
            var spec = ToolSpecification.builder().name("repo_grep").description("grep")
                .parameters(JsonObjectSchema.builder().addStringProperty("pattern").build()).build();
            for (String key : new String[] {"user-A-key", "user-B-key"}) {
                var model = AnthropicChatModel.builder().apiKey(key).baseUrl(fake.url() + "/v1/")
                    .modelName("m").maxTokens(100).build();
                ChatResponse r = model.chat(ChatRequest.builder().messages(UserMessage.from("find foo"))
                    .toolSpecifications(spec).build());
                assertThat(r.aiMessage().hasToolExecutionRequests()).isTrue();
                assertThat(r.aiMessage().toolExecutionRequests().get(0).name()).isEqualTo("repo_grep");
                assertThat(r.tokenUsage().inputTokenCount()).isEqualTo(21);
                assertThat(r.tokenUsage().outputTokenCount()).isEqualTo(9);
            }
            assertThat(fake.apiKeys).containsExactly("user-A-key", "user-B-key");
        }
    }
}
