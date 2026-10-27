package ai.devforge.repository.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * git calls that talk to a remote (clone, ls-remote, fetch) or change a clone DevForge owns. The environment is scrubbed, hooks and user
 * config are off, only https (and file, which a host adapter hands out only for tests) may be used, and the credential header travels in the
 * environment of the one process that needs it, never on a command line or in the repository's config. Raw git output is never returned to a
 * caller: it can name paths and addresses, so only the classified outcome leaves this class.
 */
@Component
class GitTransport {
    private static final long NETWORK_SECONDS = 20 * 60; // a large first clone
    private static final long LOCAL_SECONDS = 120;

    enum Outcome { OK, AUTH, NOT_FOUND, UNREACHABLE, FAILED }

    record Result(Outcome outcome, String stdout) {
        boolean ok() { return outcome == Outcome.OK; }
    }

    private static final Pattern AUTH = Pattern.compile("authentication failed|could not read username|invalid credentials|returned error: 40[13]|http/[0-9.]+ 40[13]|permission denied|access denied|forbidden");
    private static final Pattern NOT_FOUND = Pattern.compile("repository not found|returned error: 404|does not appear to be a git repository|couldn't find remote ref|not found");
    private static final Pattern UNREACHABLE = Pattern.compile("could not resolve host|connection (reset|timed out|refused)|failed to connect|ssl|tls|timed out|unable to access|network is unreachable|early eof|the remote end hung up");

    /** Reads the remote's default branch with {@code ls-remote --symref}; this is also the cheapest proof that the credential can read the repository. */
    Result lookUpDefaultBranch(String url, String authHeader) {
        Result r = run(null, authHeader, NETWORK_SECONDS, "ls-remote", "--symref", "--", url, "HEAD");
        if (!r.ok()) return r;
        for (String line : r.stdout().split("\n")) {
            if (line.startsWith("ref: refs/heads/")) return new Result(Outcome.OK, line.substring("ref: refs/heads/".length()).split("\\s+")[0]);
        }
        return new Result(Outcome.FAILED, ""); // an empty repository has no default branch to check out
    }

    Result clone(String url, Path dir, String authHeader) {
        return run(null, authHeader, NETWORK_SECONDS, "clone", "--no-tags", "--quiet", "--", url, dir.toString());
    }

    /** Points the clone at the address it was requested with (a repository can move, or the configured host can change) so a fetch never goes to a stale one. */
    Result pointOriginAt(Path dir, String url) {
        return run(dir, null, LOCAL_SECONDS, "remote", "set-url", "origin", url);
    }

    /** Fetches one branch and makes the clone's working tree exactly that commit. The clone is DevForge's own, so local changes are never kept. */
    Result updateTo(Path dir, String branch, String authHeader) {
        Result fetch = run(dir, authHeader, NETWORK_SECONDS, "fetch", "--no-tags", "--prune", "--quiet", "origin", "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        if (!fetch.ok()) return fetch;
        Result checkout = run(dir, null, LOCAL_SECONDS, "checkout", "--quiet", "--force", "-B", branch, "refs/remotes/origin/" + branch);
        if (!checkout.ok()) return checkout;
        return run(dir, null, LOCAL_SECONDS, "clean", "-ffdxq");
    }

    private Result run(Path dir, String authHeader, long timeoutSeconds, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "core.autocrlf=false", "-c", "credential.helper="));
        if (dir != null) cmd.addAll(List.of("-C", dir.toString()));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        env.put("LC_ALL", "C.UTF-8");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "/bin/false");
        env.put("GIT_ALLOW_PROTOCOL", "https:file");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        Map<String, String> extra = new HashMap<>();
        if (authHeader != null) {
            extra.put("GIT_CONFIG_COUNT", "1");
            extra.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            extra.put("GIT_CONFIG_VALUE_0", authHeader);
        }
        env.putAll(extra);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            byte[][] out = new byte[1][];
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    out[0] = p.getInputStream().readAllBytes();
                } catch (IOException e) {
                    out[0] = new byte[0];
                }
            });
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(Outcome.UNREACHABLE, "");
            }
            reader.join();
            String text = new String(out[0], StandardCharsets.UTF_8);
            if (p.exitValue() == 0) return new Result(Outcome.OK, text);
            return new Result(classify(text), "");
        } catch (IOException e) {
            return new Result(Outcome.FAILED, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, "");
        }
    }

    private static Outcome classify(String output) {
        String o = output.toLowerCase(Locale.ROOT);
        if (AUTH.matcher(o).find()) return Outcome.AUTH;
        if (UNREACHABLE.matcher(o).find()) return Outcome.UNREACHABLE;
        if (NOT_FOUND.matcher(o).find()) return Outcome.NOT_FOUND;
        return Outcome.FAILED;
    }
}
