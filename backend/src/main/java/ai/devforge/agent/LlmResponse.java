package ai.devforge.agent;

import java.util.List;

public record LlmResponse(String text, List<ToolCall> toolCalls, Usage usage, FinishReason finishReason) {
    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public enum FinishReason { END_TURN, TOOL_CALLS, MAX_TOKENS, CONTENT_FILTERED }

    /**
     * @param inputTokens       everything the model read for the call, cached or not (so budgets keep measuring the context processed)
     * @param cachedInputTokens the part of {@code inputTokens} served from the provider's prompt cache, which providers bill at a fraction of the normal price
     */
    public record Usage(long inputTokens, long outputTokens, long cachedInputTokens) {
        public Usage(long inputTokens, long outputTokens) { this(inputTokens, outputTokens, 0); }
    }
}
