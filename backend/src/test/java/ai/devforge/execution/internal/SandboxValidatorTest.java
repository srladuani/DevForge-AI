package ai.devforge.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.devforge.execution.ValidationReport;
import ai.devforge.execution.ValidationReport.Status;
import ai.devforge.execution.ValidationRequest;
import ai.devforge.execution.WorkCopy;
import ai.devforge.testsupport.FixtureRepos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The validator against a project whose "build" is a script: real sandbox, real result parsing, no network needed. */
class SandboxValidatorTest {
    private static final String REPORT = "mkdir -p build/test-results/test && printf '%s' '<testsuite tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"1\"><testcase classname=\"A\" name=\"ok\"/>"
        + "<testcase classname=\"A\" name=\"bad\"><failure message=\"x\"/></testcase><testcase classname=\"A\" name=\"skip\"><skipped/></testcase></testsuite>' > build/test-results/test/TEST-A.xml";
    @TempDir Path tmp;
    private final BubblewrapSandbox sandbox = new BubblewrapSandbox(new ExecutionProperties(
        List.of("/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/etc/alternatives", "/etc/ssl", "/etc/ca-certificates", "/etc/ld.so.cache"), List.of(), 60, 4096, 64));
    private final SandboxValidator validator = new SandboxValidator(sandbox);
    private WorkCopy copy;

    @BeforeEach
    void open() throws IOException {
        assumeTrue(sandbox.available(), "bubblewrap is required");
        Path source = FixtureRepos.commit(tmp.resolve("src"), Map.of("README.md", "x\n", ".gitignore", "build/\n")).toAbsolutePath();
        copy = new ScratchCopies(tmp.resolve("work").toString()).open("v", source, FixtureRepos.git(source, "rev-parse", "HEAD").trim());
    }

    @AfterEach
    void close() { if (copy != null) copy.close(); }

    private ValidationReport run(String tool, String command) { return validator.validate(copy, new ValidationRequest(tool, "", command), tmp.resolve("cache"), () -> false); }

    @Test
    void failingTestsAreReportedWithTheirNamesAndCounts() {
        ValidationReport r = run("script", REPORT + "; exit 1");
        assertThat(r.status()).isEqualTo(Status.FAILED);
        assertThat(r.tests()).isEqualTo(3);
        assertThat(r.failures()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.failingTests()).containsExactly("A.bad");
    }

    @Test
    void aGreenRunIsPassedAndTheBuildOutputDoesNotLeakIntoTheDiff() {
        ValidationReport r = run("script", "echo all good; mkdir -p build/test-results/test && printf '%s' '<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"B\" name=\"a\"/><testcase classname=\"B\" name=\"b\"/></testsuite>' > build/test-results/test/TEST-B.xml");
        assertThat(r.status()).isEqualTo(Status.PASSED);
        assertThat(r.tests()).isEqualTo(2);
        assertThat(r.outputTail()).contains("all good");
        assertThat(copy.diff().isEmpty()).as("ignored build output is not part of the change").isTrue();
    }

    @Test
    void ZeroReportsMeansTheExitCodeIsAllWeKnowAndTheNoteSaysSo() {
        ValidationReport r = run("script", "true");
        assertThat(r.status()).isEqualTo(Status.PASSED);
        assertThat(r.tests()).isEqualTo(-1);
        assertThat(r.note()).contains("No test report");
    }

    @Test
    void theTestStepHasNoNetworkAndSeesNoHostFiles() {
        ValidationReport r = run("script", "test \"$(tail -n +3 /proc/net/dev | wc -l)\" = 1");
        assertThat(r.status()).as(r.outputTail()).isEqualTo(Status.PASSED);
    }

    @Test
    void unknownToolsAndCancellationAreNotRunAndNeverAPass() {
        assertThat(run("cobol", null).status()).isEqualTo(Status.NOT_RUN);
        assertThat(run("script", null).status()).isEqualTo(Status.NOT_RUN);
        ValidationReport cancelled = validator.validate(copy, new ValidationRequest("script", "", "true"), tmp.resolve("cache"), () -> true);
        assertThat(cancelled.status()).isEqualTo(Status.NOT_RUN);
        assertThat(Set.of(Status.NOT_RUN)).contains(cancelled.status());
    }

    @Test
    void maliciousReportXmlIsNotExpanded() {
        ValidationReport r = run("script", "mkdir -p build/test-results/test && printf '%s' '<!DOCTYPE x [<!ENTITY a \"boom\">]><testsuite tests=\"9\" failures=\"0\">&a;</testsuite>' > build/test-results/test/TEST-X.xml");
        assertThat(r.tests()).as("a report with a DTD is ignored").isEqualTo(-1);
    }
}
