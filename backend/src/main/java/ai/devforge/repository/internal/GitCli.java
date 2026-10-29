package ai.devforge.repository.internal;

import ai.devforge.platform.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Minimal, hardened wrapper around the git executable. Arguments are passed as an array (no shell), the environment is scrubbed,
 * optional locks are disabled so reads never touch the index, and every call has a timeout and an output cap.
 */
@Component
class GitCli {
    private static final long TIMEOUT_SECONDS = 20;
    private static final int MAX_OUTPUT = 4 * 1024 * 1024;

    record Output(String text, boolean truncated) {}

    Output run(Path repo, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.quotepath=false", "-c", "core.fsmonitor=false", "-C", repo.toString()));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean[] truncated = {false};
            Thread reader = Thread.ofVirtual().start(() -> truncated[0] = drain(p.getInputStream(), out));
            Thread err = Thread.ofVirtual().start(() -> drain(p.getErrorStream(), new ByteArrayOutputStream()));
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw DomainException.unprocessable("GIT_TIMEOUT", "git did not finish in time");
            }
            reader.join();
            err.join();
            if (p.exitValue() > 1) throw new GitFailure(p.exitValue());
            return new Output(out.toString(StandardCharsets.UTF_8), truncated[0]);
        } catch (IOException e) {
            throw DomainException.unprocessable("GIT_UNAVAILABLE", "git could not be executed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw DomainException.unprocessable("GIT_INTERRUPTED", "git call interrupted");
        }
    }

    /** Exit code 2+ from git (exit 1 is "no match" for grep and is not an error). */
    static final class GitFailure extends RuntimeException {
        final int exit;
        GitFailure(int exit) { super("git exited with " + exit); this.exit = exit; }
    }

    private static boolean drain(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        boolean truncated = false;
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() < MAX_OUTPUT) out.write(buf, 0, Math.min(n, MAX_OUTPUT - out.size()));
                else truncated = true;
            }
        } catch (IOException ignored) {
            // process ended
        }
        return truncated;
    }

    String headSha(Path repo) {
        try {
            String s = run(repo, "rev-parse", "--verify", "HEAD").text().trim();
            if (!s.matches("[0-9a-f]{40,64}")) throw new GitFailure(1);
            return s;
        } catch (GitFailure e) {
            throw DomainException.unprocessable("REPOSITORY_EMPTY", "Repository has no commits");
        }
    }

    String currentBranch(Path repo) {
        try {
            String b = run(repo, "symbolic-ref", "--short", "-q", "HEAD").text().trim();
            return b.isEmpty() ? "HEAD" : b;
        } catch (GitFailure e) {
            return "HEAD";
        }
    }

    List<String> lsTree(Path repo, String sha) {
        String raw = run(repo, "ls-tree", "-r", "-z", "--name-only", sha).text();
        return raw.isEmpty() ? List.of() : Arrays.stream(raw.split("\0")).filter(s -> !s.isEmpty()).toList();
    }

    /** File content at sha, or null if absent/binary. */
    String cat(Path repo, String sha, String path) {
        try {
            String t = run(repo, "show", sha + ":" + path).text();
            return t.indexOf('\0') >= 0 ? null : t;
        } catch (GitFailure e) {
            return null;
        }
    }

    String verifySha(Path repo, String sha) {
        if (sha == null || !sha.matches("[0-9a-fA-F]{7,64}")) throw DomainException.badRequest("INVALID_SHA", "Invalid commit id");
        try {
            String full = run(repo, "rev-parse", "--verify", "--quiet", sha + "^{commit}").text().trim();
            if (full.isEmpty()) throw new GitFailure(1);
            return full;
        } catch (GitFailure e) {
            throw DomainException.unprocessable("UNKNOWN_COMMIT", "Commit not found in repository");
        }
    }
}
