package ai.devforge.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class LangChain4jOpenAiSpikeTest {
    @Test
    void sameShapeAsAnthropicAdapter() throws Exception {
        List<String> auth = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            auth.add(ex.getRequestHeaders().getFirst("authorization"));
            byte[] b = ("{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"repo_grep\",\"arguments\":\"{\\\"pattern\\\":\\\"foo\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":9,\"total_tokens\":30}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        try {
            var spec = ToolSpecification.builder().name("repo_grep").description("grep")
                .parameters(JsonObjectSchema.builder().addStringProperty("pattern").build()).build();
            for (String key : new String[] {"user-A-key", "user-B-key"}) {
                var model = OpenAiChatModel.builder().apiKey(key).baseUrl("http://127.0.0.1:" + s.getAddress().getPort() + "/v1")
                    .modelName("m").build();
                var r = model.chat(ChatRequest.builder().messages(UserMessage.from("find foo")).toolSpecifications(spec).build());
                assertThat(r.aiMessage().hasToolExecutionRequests()).isTrue();
                assertThat(r.aiMessage().toolExecutionRequests().get(0).arguments()).contains("foo");
                assertThat(r.tokenUsage().inputTokenCount()).isEqualTo(21);
            }
            assertThat(auth).containsExactly("Bearer user-A-key", "Bearer user-B-key");
        } finally { s.stop(0); }
    }
}
