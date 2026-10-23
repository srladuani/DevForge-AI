package ai.devforge.agent;

import java.util.List;

public record LlmResponse(String text, List<ToolCall> toolCalls, Usage usage, FinishReason finishReason) {
    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public enum FinishReason { END_TURN, TOOL_CALLS, MAX_TOKENS, CONTENT_FILTERED }

    public record Usage(long inputTokens, long outputTokens) {}
}
