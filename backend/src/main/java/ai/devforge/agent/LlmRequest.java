package ai.devforge.agent;

import java.time.Duration;
import java.util.List;

/** @param responseSchemaJson if set, the final answer must be JSON matching this schema */
public record LlmRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools, String responseSchemaJson, int maxOutputTokens, Duration timeout) {
    public LlmRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public record ChatMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId, String toolName) {
        public static ChatMessage system(String text) { return new ChatMessage(Role.SYSTEM, text, null, null, null); }

        public static ChatMessage user(String text) { return new ChatMessage(Role.USER, text, null, null, null); }

        public static ChatMessage assistant(String text, List<ToolCall> calls) { return new ChatMessage(Role.ASSISTANT, text, calls, null, null); }

        public static ChatMessage toolResult(String callId, String toolName, String text) { return new ChatMessage(Role.TOOL, text, null, callId, toolName); }

        public ChatMessage {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
    }

    public record ToolSpec(String name, String description, String inputSchemaJson) {}
}
