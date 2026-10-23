package ai.devforge.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.devforge.execution.ValidationReport;
import ai.devforge.execution.ValidationReport.Status;
import ai.devforge.execution.ValidationRequest;
import ai.devforge.execution.WorkCopy;
import ai.devforge.testsupport.FixtureRepos;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in: runs the real npm recipe (online dependency step, offline test step) in the sandbox on a tiny project with no dependencies.
 * Needs a Node install visible to the sandbox: {@code DEVFORGE_SMOKE_NODE_HOME=/path/to/node/install ./mvnw test -Dtest=ToolchainSmokeTest}.
 */
class ToolchainSmokeTest {
    @TempDir Path tmp;

    @Test
    void npmProjectRunsThroughTheDependencyStepAndTheOfflineTestStep() throws Exception {
        String nodeHome = System.getenv("DEVFORGE_SMOKE_NODE_HOME");
        assumeTrue(nodeHome != null && !nodeHome.isBlank(), "set DEVFORGE_SMOKE_NODE_HOME to run the real-toolchain smoke test");
        BubblewrapSandbox sandbox = new BubblewrapSandbox(new ExecutionProperties(
            List.of("/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/etc/alternatives", "/etc/ssl", "/etc/ca-certificates", "/etc/ld.so.cache", "/etc/resolv.conf", "/etc/hosts", "/etc/nsswitch.conf"), List.of(nodeHome), 300, 4096, 64));
        assumeTrue(sandbox.available());
        Path source = FixtureRepos.commit(tmp.resolve("src"), Map.of(
            ".gitignore", "node_modules/\nreports/\n",
            "package.json", "{\"name\":\"smoke\",\"version\":\"1.0.0\",\"scripts\":{\"test\":\"mkdir -p reports && printf '<testsuite tests=\\\"2\\\" failures=\\\"0\\\" errors=\\\"0\\\" skipped=\\\"0\\\"><testcase classname=\\\"S\\\" name=\\\"a\\\"/><testcase classname=\\\"S\\\" name=\\\"b\\\"/></testsuite>' > reports/junit.xml && node -e \\\"console.log('network? ' + require('os').networkInterfaces().eth0)\\\"\"}}",
            "package-lock.json", "{\"name\":\"smoke\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"requires\":true,\"packages\":{\"\":{\"name\":\"smoke\",\"version\":\"1.0.0\"}}}"));
        String sha = FixtureRepos.git(source, "rev-parse", "HEAD").trim();
        try (WorkCopy copy = new ScratchCopies(tmp.resolve("work").toString()).open("smoke", source, sha)) {
            ValidationReport r = new SandboxValidator(sandbox).validate(copy, new ValidationRequest("npm", "", "npm test"), tmp.resolve("cache"), () -> false);
            assertThat(r.status()).as(r.note() + "\n" + r.outputTail()).isEqualTo(Status.PASSED);
            assertThat(r.tests()).isEqualTo(2);
            assertThat(copy.diff().isEmpty()).as("node_modules and reports stay out of the change").isTrue();
        }
    }
}
