package ai.devforge.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One command to run isolated. {@code workDir} is the only writable host directory (visible as /work). Network is off unless
 * {@code network} is set, which only the dependency-warming step may do. {@code cacheDir} is a second writable directory (visible as /cache)
 * that survives between steps so dependencies fetched while online can be used offline.
 */
public record SandboxSpec(Path workDir, String command, Duration timeout, boolean network, Map<String, String> env, long maxOutputBytes, @Nullable Path cacheDir) {
    public SandboxSpec {
        env = Map.copyOf(env);
    }

    public SandboxSpec(Path workDir, String command, Duration timeout, boolean network, Map<String, String> env, long maxOutputBytes) { this(workDir, command, timeout, network, env, maxOutputBytes, null); }

    public static SandboxSpec offline(Path workDir, String command, Duration timeout) { return new SandboxSpec(workDir, command, timeout, false, Map.of(), 256 * 1024); }
}
