package ai.devforge.agent;

/** One tool call the agent made, for the run timeline. */
public record ToolTrace(String tool, String arguments, String resultSummary) {}
