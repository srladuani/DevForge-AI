package ai.devforge.execution;

import java.time.Duration;

/** @param output stdout and stderr together, credential-scrubbed and size-capped; it is untrusted data, like anything a repo's code prints */
public record SandboxResult(int exitCode, boolean timedOut, boolean truncated, String output, Duration duration, String backend) {
    public boolean succeeded() { return !timedOut && exitCode == 0; }
}
