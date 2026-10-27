package ai.devforge.agent;

/** Lets an agent step report the tokens it spent, so the run shows real usage rather than an estimate. */
public interface UsageRecorder {
    /** @param cachedInputTokens the part of {@code inputTokens} that came from the provider's prompt cache */
    void record(String step, long inputTokens, long outputTokens, long cachedInputTokens);

    default void record(String step, long inputTokens, long outputTokens) { record(step, inputTokens, outputTokens, 0); }
}
