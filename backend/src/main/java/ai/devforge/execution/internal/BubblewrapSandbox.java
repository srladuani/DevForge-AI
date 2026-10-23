package ai.devforge.execution.internal;

import ai.devforge.execution.Sandbox;
import ai.devforge.execution.SandboxResult;
import ai.devforge.execution.SandboxSpec;
import ai.devforge.execution.SandboxUnavailableException;
import ai.devforge.platform.Redactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Rootless isolation with bubblewrap: fresh user/pid/ipc/uts namespaces, no network unless the spec asks, the host's system directories
 * read-only, no home directory, a scrubbed environment, and a single writable directory. If the process dies or times out, the namespace
 * (and everything in it) goes with it.
 *
 * <p>Limits: CPU time, process count and file size are enforced with prlimit; memory is not (bubblewrap has no cgroup support). The Docker
 * backend planned in ADR-0006 is the one with memory limits.
 */
@Component
class BubblewrapSandbox implements Sandbox {
    private static final String BASE_PATH = "/usr/local/bin:/usr/bin:/bin";
    private final ExecutionProperties props;
    private volatile Boolean available;

    BubblewrapSandbox(ExecutionProperties props) { this.props = props; }

    @Override public String backend() { return "bubblewrap"; }

    @Override
    public boolean available() {
        Boolean a = available;
        if (a == null) available = a = smokeTest();
        return a;
    }

    private boolean smokeTest() {
        try {
            Path dir = Files.createTempDirectory("devforge-sbx-probe");
            try {
                SandboxResult r = execute(SandboxSpec.offline(dir, "true", Duration.ofSeconds(10)));
                return r.succeeded();
            } finally {
                Files.deleteIfExists(dir);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        if (!available()) throw new SandboxUnavailableException("bubblewrap is not installed or user namespaces are disabled; code is not run without isolation");
        return execute(spec);
    }

    private SandboxResult execute(SandboxSpec spec) {
        List<String> cmd = new ArrayList<>(List.of("bwrap", "--unshare-user", "--unshare-pid", "--unshare-ipc", "--unshare-uts", "--unshare-cgroup-try", "--die-with-parent", "--new-session"));
        if (!spec.network()) cmd.add("--unshare-net");
        Set<String> mounted = new LinkedHashSet<>();
        for (String m : props.readOnlyMounts()) mount(cmd, m, mounted);
        for (String m : props.toolchainMounts()) mount(cmd, m, mounted);
        String javaHome = System.getProperty("java.home");
        mount(cmd, javaHome, mounted);
        cmd.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--dir", "/tmp/home", "--bind", spec.workDir().toAbsolutePath().toString(), "/work", "--chdir", "/work", "--clearenv"));
        if (spec.cacheDir() != null) cmd.addAll(List.of("--bind", spec.cacheDir().toAbsolutePath().toString(), "/cache"));
        StringBuilder path = new StringBuilder();
        for (String m : props.toolchainMounts()) if (Files.isDirectory(Path.of(m, "bin"))) path.append(m).append("/bin:");
        env(cmd, "PATH", path + javaHome + "/bin:" + BASE_PATH);
        env(cmd, "JAVA_HOME", javaHome);
        env(cmd, "HOME", "/tmp/home");
        env(cmd, "LANG", "C.UTF-8");
        env(cmd, "CI", "true");
        for (Map.Entry<String, String> e : spec.env().entrySet()) env(cmd, e.getKey(), e.getValue());
        cmd.addAll(List.of("--", "prlimit", "--cpu=" + props.cpuSeconds(), "--nproc=" + props.maxProcesses(), "--fsize=" + props.maxFileMegabytes() * 1024L * 1024L, "--", "sh", "-c", spec.command()));

        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH", BASE_PATH);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean[] truncated = {false};
            Thread reader = Thread.ofVirtual().start(() -> truncated[0] = drain(p.getInputStream(), out, spec.maxOutputBytes()));
            boolean finished = p.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(5000);
            String text = Redactor.redact(out.toString(StandardCharsets.UTF_8));
            return new SandboxResult(finished ? p.exitValue() : -1, !finished, truncated[0], text, Duration.between(start, Instant.now()), backend());
        } catch (IOException e) {
            throw new SandboxUnavailableException("could not start bubblewrap: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxUnavailableException("interrupted while running a sandboxed command");
        }
    }

    private static void env(List<String> cmd, String k, String v) { cmd.addAll(List.of("--setenv", k, v)); }

    /** Symlinked system directories (/bin -> usr/bin) are recreated as links; real ones are bound read-only. Missing paths are skipped. */
    private static void mount(List<String> cmd, String path, Set<String> mounted) {
        Path p = Path.of(path);
        if (!mounted.add(path) || !Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.isSymbolicLink(p)) cmd.addAll(List.of("--symlink", Files.readSymbolicLink(p).toString(), path));
            else cmd.addAll(List.of("--ro-bind", path, path));
        } catch (IOException e) {
            // an unreadable mount point is simply absent inside the sandbox
        }
    }

    /** Reads everything so the child never blocks on a full pipe, keeping only the first {@code max} bytes. */
    private static boolean drain(InputStream in, ByteArrayOutputStream out, long max) {
        boolean truncated = false;
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                long room = max - out.size();
                if (room > 0) out.write(buf, 0, (int) Math.min(n, room));
                if (n > room) truncated = true;
            }
        } catch (IOException e) {
            // the process ended; what was read is what we have
        }
        return truncated;
    }
}
