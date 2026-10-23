package ai.devforge.agent;

/** Lets an agent step report the tokens it spent, so the run shows real usage rather than an estimate. */
@FunctionalInterface
public interface UsageRecorder {
    void record(String step, long inputTokens, long outputTokens);
}
