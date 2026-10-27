package ai.devforge.agent.internal.anthropic;

import ai.devforge.agent.CredentialContext;
import ai.devforge.agent.LlmException;
import ai.devforge.agent.LlmRequest;
import ai.devforge.agent.LlmRequest.ChatMessage;
import ai.devforge.agent.LlmRequest.ToolSpec;
import ai.devforge.agent.LlmResponse;
import ai.devforge.agent.ToolCall;
import ai.devforge.agent.internal.ProviderLlmClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The Claude adapter. It builds a fresh client for every call from the credential lent for that call, so a key is never held between calls
 * and can never be used for anyone else's run. Failures are mapped to the provider-neutral taxonomy; nothing from the request (prompt, code,
 * key) is ever written to a log or an exception message. This is the only class that talks to the vendor library for chat.
 */
@Component
class AnthropicLlmClient implements ProviderLlmClient {
    /** How much of each request the provider may cache (cached input is billed at a fraction of normal input). */
    enum Caching { OFF, PREFIX, AUTO }

    private final AnthropicEndpoint endpoint;
    private final Caching caching;

    /**
     * @param caching {@code OFF}; {@code PREFIX} caches the system prompt and tool definitions (the stable start of every request); {@code AUTO}
     *                also asks the provider to cache the growing conversation, which is what a long tool loop re-sends on every turn. An
     *                unrecognised value means PREFIX.
     */
    @org.springframework.beans.factory.annotation.Autowired
    AnthropicLlmClient(AnthropicEndpoint endpoint, @org.springframework.beans.factory.annotation.Value("${devforge.agent.prompt-caching:PREFIX}") String caching) {
        this.endpoint = endpoint;
        Caching parsed = Caching.PREFIX;
        for (Caching c : Caching.values()) if (c.name().equalsIgnoreCase(caching == null ? "" : caching.strip())) parsed = c;
        this.caching = parsed;
    }

    AnthropicLlmClient(AnthropicEndpoint endpoint) { this(endpoint, "PREFIX"); }

    @Override public String provider() { return "ANTHROPIC"; }

    @Override
    public LlmResponse complete(LlmRequest request, CredentialContext credential) {
        String secret = credential.secret().get(); // lent for this call only; never stored in a field
        try {
            var builder = AnthropicChatModel.builder().apiKey(secret).baseUrl(endpoint.baseUrl() + "/v1/").modelName(request.model())
                .maxTokens(request.maxOutputTokens()).timeout(request.timeout()).maxRetries(0).logRequests(false).logResponses(false);
            if (caching != Caching.OFF) builder.cacheSystemMessages(true).cacheTools(true);
            if (caching == Caching.AUTO) builder.cacheAutomatically(true);
            AnthropicChatModel model = builder.build();
            ChatRequest.Builder req = ChatRequest.builder().messages(toMessages(request.messages()));
            if (!request.tools().isEmpty()) req.toolSpecifications(toSpecs(request.tools()));
            ChatResponse r = model.chat(req.build());
            return fromResponse(r);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw AnthropicErrors.map(e, secret);
        }
    }

    private static List<dev.langchain4j.data.message.ChatMessage> toMessages(List<ChatMessage> in) {
        List<dev.langchain4j.data.message.ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : in) {
            switch (m.role()) {
                case SYSTEM -> out.add(SystemMessage.from(m.text()));
                case USER -> out.add(UserMessage.from(m.text()));
                case ASSISTANT -> {
                    List<ToolExecutionRequest> calls = m.toolCalls().stream().map(c -> ToolExecutionRequest.builder().id(c.id()).name(c.name()).arguments(c.argumentsJson()).build()).toList();
                    out.add(calls.isEmpty() ? AiMessage.from(m.text() == null ? "" : m.text()) : AiMessage.from(m.text() == null || m.text().isBlank() ? null : m.text(), calls));
                }
                case TOOL -> out.add(ToolExecutionResultMessage.from(m.toolCallId(), m.toolName(), m.text()));
            }
        }
        return out;
    }

    private static List<ToolSpecification> toSpecs(List<ToolSpec> tools) {
        return tools.stream().map(t -> ToolSpecification.builder().name(t.name()).description(t.description()).parameters(SchemaConverter.objectSchema(t.inputSchemaJson())).build()).toList();
    }

    private static LlmResponse fromResponse(ChatResponse r) {
        AiMessage ai = r.aiMessage();
        List<ToolCall> calls = ai.hasToolExecutionRequests() ? ai.toolExecutionRequests().stream().map(t -> new ToolCall(t.id(), t.name(), t.arguments() == null ? "{}" : t.arguments())).toList() : List.of();
        long in = r.tokenUsage() == null || r.tokenUsage().inputTokenCount() == null ? 0 : r.tokenUsage().inputTokenCount();
        long out = r.tokenUsage() == null || r.tokenUsage().outputTokenCount() == null ? 0 : r.tokenUsage().outputTokenCount();
        // The provider reports cached input separately from "input_tokens"; add it back so the total is everything the model read.
        long cached = 0;
        if (r.tokenUsage() instanceof dev.langchain4j.model.anthropic.AnthropicTokenUsage a) {
            cached = a.cacheReadInputTokens() == null ? 0 : a.cacheReadInputTokens();
            in += cached + (a.cacheCreationInputTokens() == null ? 0 : a.cacheCreationInputTokens());
        }
        LlmResponse.FinishReason reason = !calls.isEmpty() ? LlmResponse.FinishReason.TOOL_CALLS : switch (String.valueOf(r.finishReason())) {
            case "LENGTH" -> LlmResponse.FinishReason.MAX_TOKENS;
            case "CONTENT_FILTER" -> LlmResponse.FinishReason.CONTENT_FILTERED;
            default -> LlmResponse.FinishReason.END_TURN;
        };
        return new LlmResponse(ai.text(), calls, new LlmResponse.Usage(in, out, cached), reason);
    }
}
