package ai.devforge.execution.internal;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.devforge.execution.ValidationReport;
import ai.devforge.execution.ValidationRequest;
import ai.devforge.execution.WorkCopy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in: validates a stored patch on a real Gradle repository through the real sandbox (dependency step with network, then offline tests).
 * The repository is only read (git archive); the build happens in a temporary copy. Needs bubblewrap, network and:
 * DEVFORGE_SMOKE_REPO (checkout path), DEVFORGE_SMOKE_SHA, DEVFORGE_SMOKE_PATCH (file), optional DEVFORGE_SMOKE_TEST_COMMAND (default ./gradlew test).
 */
class GradleProjectSmokeTest {
    @TempDir Path tmp;

    @Test
    void aStoredPatchBuildsAndItsTestsRunInTheSandbox() throws Exception {
        String repo = System.getenv("DEVFORGE_SMOKE_REPO"), sha = System.getenv("DEVFORGE_SMOKE_SHA"), patch = System.getenv("DEVFORGE_SMOKE_PATCH");
        assumeTrue(repo != null && sha != null && patch != null, "set DEVFORGE_SMOKE_REPO, DEVFORGE_SMOKE_SHA and DEVFORGE_SMOKE_PATCH to run this");
        var defaults = org.springframework.boot.context.properties.bind.Binder.get(new org.springframework.core.env.StandardEnvironment()).bindOrCreate("devforge.execution", ExecutionProperties.class);
        BubblewrapSandbox sandbox = new BubblewrapSandbox(defaults);
        assumeTrue(sandbox.available());
        String command = System.getenv().getOrDefault("DEVFORGE_SMOKE_TEST_COMMAND", "./gradlew test");
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "devforge-smoke-cache");
        try (WorkCopy copy = new ScratchCopies(tmp.resolve("work").toString()).open("smoke", Path.of(repo), sha.strip())) {
            copy.applyPatch(Files.readString(Path.of(patch)), Set.of());
            ValidationReport r = new SandboxValidator(sandbox).validate(copy, new ValidationRequest("gradle", "", command), cache, () -> false);
            String text = r.status() + " | tests=" + r.tests() + " failures=" + r.failures() + " errors=" + r.errors() + " skipped=" + r.skipped() + "\nfailing=" + r.failingTests() + "\nnote=" + r.note() + "\ncommand=" + r.command()
                + "\n--- output tail ---\n" + r.outputTail();
            Files.createDirectories(Path.of("build"));
            Files.writeString(Path.of("build", "smoke-gradle.txt"), text);
        }
    }
}
