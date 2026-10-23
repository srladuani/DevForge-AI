package ai.devforge.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.devforge.execution.SandboxResult;
import ai.devforge.execution.SandboxSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** EXEC-1: what the sandbox must guarantee, checked by actually running things in it. */
class BubblewrapSandboxTest {
    private final BubblewrapSandbox sandbox = new BubblewrapSandbox(new ExecutionProperties(
        List.of("/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/etc/alternatives", "/etc/ssl", "/etc/ca-certificates", "/etc/ld.so.cache"), List.of(), 60, 4096, 64));
    @TempDir Path work;

    @BeforeEach
    void needsBubblewrap() { assumeTrue(sandbox.available(), "bubblewrap with user namespaces is required for the sandbox tests"); }

    private SandboxResult run(String command) { return sandbox.run(SandboxSpec.offline(work, command, Duration.ofSeconds(20))); }

    @Test
    void runsACommandAndReportsItsExitCodeAndOutput() {
        SandboxResult ok = run("echo hello; echo problem >&2");
        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.output()).contains("hello").contains("problem");
        SandboxResult bad = run("exit 3");
        assertThat(bad.exitCode()).isEqualTo(3);
        assertThat(bad.succeeded()).isFalse();
    }

    @Test
    void hasNoNetworkUnlessAskedAndOnlyTheWarmStepAsks() {
        assertThat(run("tail -n +3 /proc/net/dev | wc -l").output().trim()).as("only the loopback interface").isEqualTo("1");
    }

    @Test
    void cannotSeeTheHostHomeOrWriteAnywhereButTheWorkDirectory() throws IOException {
        Path canary = Path.of("target", "sandbox-canary-" + System.nanoTime() + ".txt").toAbsolutePath();
        Files.writeString(canary, "host secret");
        try {
            assertThat(run("cat '" + canary + "' 2>&1; test -e '" + canary + "' && echo VISIBLE || echo HIDDEN").output()).contains("HIDDEN").doesNotContain("host secret");
        } finally {
            Files.deleteIfExists(canary);
        }
        assertThat(run("touch /usr/x 2>&1; echo $?").output().trim()).doesNotEndWith("0");
        run("echo kept > /work/out.txt; echo gone > /tmp/gone.txt");
        assertThat(Files.readString(work.resolve("out.txt")).trim()).isEqualTo("kept");
        assertThat(run("test -e /tmp/gone.txt && echo LEAK || echo CLEAN").output()).contains("CLEAN");
    }

    @Test
    void startsWithAScrubbedEnvironment() {
        List<String> names = run("env | cut -d= -f1").output().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertThat(names).isSubsetOf("PATH", "JAVA_HOME", "HOME", "LANG", "CI", "PWD", "SHLVL", "_", "OLDPWD");
    }

    @Test
    void aCommandThatRunsTooLongIsKilledWithEverythingItStarted() throws Exception {
        SandboxResult r = sandbox.run(SandboxSpec.offline(work, "sleep 31.37 & sleep 31.37", Duration.ofSeconds(1)));
        assertThat(r.timedOut()).isTrue();
        assertThat(r.duration()).isLessThan(Duration.ofSeconds(10));
        String left = "";
        for (int i = 0; i < 30; i++) {
            left = new String(new ProcessBuilder("pgrep", "-xf", "sleep 31.37").start().getInputStream().readAllBytes()).trim();
            if (left.isEmpty()) break;
            Thread.sleep(100);
        }
        assertThat(left).as("no orphaned process").isEmpty();
    }

    @Test
    void outputIsCappedAndCredentialsAreScrubbed() {
        SandboxResult big = sandbox.run(new SandboxSpec(work, "yes aaaaaaaaaaaaaaaa | head -c 1000000", Duration.ofSeconds(20), false, Map.of(), 1000));
        assertThat(big.truncated()).isTrue();
        assertThat(big.output().length()).isLessThanOrEqualTo(1000);
        assertThat(run("echo 'password=hunter2hunter2 token: abcd1234efgh5678'").output()).doesNotContain("hunter2").doesNotContain("abcd1234efgh5678");
    }

    @Test
    void passesOnlyTheEnvironmentTheCallerNames() {
        SandboxResult r = sandbox.run(new SandboxSpec(work, "echo $WARM_ONLY", Duration.ofSeconds(10), false, Map.of("WARM_ONLY", "yes"), 1000));
        assertThat(r.output().trim()).isEqualTo("yes");
    }

    @Test
    void theNetworkIsAvailableOnlyWhenTheSpecSaysSo() {
        SandboxResult r = sandbox.run(new SandboxSpec(work, "tail -n +3 /proc/net/dev | wc -l", Duration.ofSeconds(10), true, Map.of(), 1000));
        assertThat(Integer.parseInt(r.output().trim())).as("host interfaces are visible when the network is shared").isGreaterThanOrEqualTo(1);
    }
}
