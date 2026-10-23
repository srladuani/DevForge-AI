package ai.devforge.execution.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the git executable against a scratch copy with a scrubbed environment, no hooks, no user config, a timeout and no shell. */
final class GitProcess {
    private GitProcess() {}

    record Result(int exit, String stdout) {}

    static Result run(Path dir, String... args) { return runWithInput(dir, null, args); }

    static Result runWithInput(Path dir, String stdin, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "core.autocrlf=false", "-C", dir.toString()));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        pb.environment().put("GIT_AUTHOR_NAME", "DevForge");
        pb.environment().put("GIT_AUTHOR_EMAIL", "devforge@localhost");
        pb.environment().put("GIT_COMMITTER_NAME", "DevForge");
        pb.environment().put("GIT_COMMITTER_EMAIL", "devforge@localhost");
        try {
            Process p = pb.start();
            try (var in = p.getOutputStream()) {
                if (stdin != null) in.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // git may stop reading early (bad patch); its exit code tells the story
            }
            byte[][] out = new byte[1][];
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    out[0] = p.getInputStream().readAllBytes();
                } catch (IOException e) {
                    out[0] = new byte[0];
                }
            });
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("git timed out");
            }
            t.join();
            return new Result(p.exitValue(), new String(out[0], StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("git could not be executed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git interrupted");
        }
    }
}
