package ai.devforge.agent;

/** A tool invocation the model asked for. It is only a request: the platform decides whether and how it runs. */
public record ToolCall(String id, String name, String argumentsJson) {}
