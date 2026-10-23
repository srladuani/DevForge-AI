package ai.devforge.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devforge.execution.WorkCopy;
import ai.devforge.execution.WorkCopy.EditRejected;
import ai.devforge.testsupport.FixtureRepos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkCopyTest {
    private static final String BO = "src/main/java/com/acme/video/TrainingVideoBO.java";
    @TempDir Path tmp;
    private Path source;
    private String sha;
    private WorkCopy copy;

    @BeforeEach
    void open() throws IOException {
        source = FixtureRepos.gradleSpring(tmp.resolve("source"));
        FixtureRepos.write(source, Map.of(".env", "SECRET=1\n", "build.gradle", "plugins { }\n"));
        FixtureRepos.git(source, "add", "-f", ".");
        FixtureRepos.git(source, "commit", "-q", "-m", "more");
        sha = FixtureRepos.git(source, "rev-parse", "HEAD").trim();
        copy = new ScratchCopies(tmp.resolve("work").toString()).open("run-1", source, sha);
    }

    @AfterEach
    void close() { copy.close(); }

    @Test
    void exportsTheCommitIntoAPrivateDirectoryAndLeavesTheSourceUntouched() throws IOException {
        String head = FixtureRepos.git(source, "status", "--porcelain");
        assertThat(copy.root()).startsWith(tmp.resolve("work"));
        assertThat(copy.root().startsWith(source)).isFalse();
        assertThat(copy.read(BO)).contains("videosFor");
        assertThat(copy.diff().isEmpty()).isTrue();
        copy.edit(BO, "videosFor", "videosForRole", Set.of());
        assertThat(FixtureRepos.git(source, "status", "--porcelain")).isEqualTo(head);
        assertThat(Files.readString(source.resolve(BO))).doesNotContain("videosForRole");
        assertThat(Files.exists(copy.root().resolve(".git"))).isTrue();
    }

    @Test
    void anEditBecomesAUnifiedDiffNamingTheChangedFiles() {
        copy.edit(BO, "videosFor", "videosForRole", Set.of());
        copy.createFile("src/main/java/com/acme/video/Notes.txt", "hello\n", Set.of());
        var diff = copy.diff();
        assertThat(diff.files()).containsExactlyInAnyOrder(BO, "src/main/java/com/acme/video/Notes.txt");
        assertThat(diff.patch()).contains("--- a/" + BO).contains("+++ b/" + BO).contains("-").contains("+hello");
    }

    @Test
    void anEditMustMatchExactlyOnce() {
        assertThatThrownBy(() -> copy.edit(BO, "no such text anywhere", "x", Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("NO_MATCH"));
        assertThatThrownBy(() -> copy.edit(BO, "", "x", Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("EMPTY_MATCH"));
        assertThatThrownBy(() -> copy.edit(BO, "public", "x", Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("AMBIGUOUS_MATCH"));
        assertThat(copy.diff().isEmpty()).as("a rejected edit changes nothing").isTrue();
    }

    @Test
    void pathsCannotEscapeTheCopyOrReachSecrets() throws IOException {
        for (String bad : new String[] {"../outside.txt", "/etc/passwd", "a/../../b", ".git/config", "src\\x", "", "src//x"}) {
            assertThatThrownBy(() -> copy.createFile(bad, "x", Set.of())).as(bad).isInstanceOf(EditRejected.class);
        }
        assertThatThrownBy(() -> copy.read(".env")).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("RESTRICTED_PATH"));
        assertThatThrownBy(() -> copy.edit(".env", "SECRET", "X", Set.of(".env"))).as("approval never unlocks a secret file").isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("RESTRICTED_PATH"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Files.createSymbolicLink(copy.root().resolve("link"), outside);
        assertThatThrownBy(() -> copy.createFile("link/escaped.txt", "x", Set.of())).isInstanceOf(EditRejected.class);
        assertThat(Files.exists(outside.resolve("escaped.txt"))).isFalse();
    }

    @Test
    void buildAndCiConfigurationChangesOnlyWhenTheApprovedProposalNamedIt() {
        assertThatThrownBy(() -> copy.edit("build.gradle", "plugins", "plugins2", Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("PROTECTED_PATH"));
        copy.edit("build.gradle", "plugins", "plugins2", Set.of("build.gradle"));
        assertThat(copy.diff().files()).containsExactly("build.gradle");
    }

    @Test
    void sizeAndContentLimitsAreEnforced() {
        assertThatThrownBy(() -> copy.createFile("big.txt", "x".repeat(GitWorkCopy.MAX_FILE_BYTES + 1), Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("TOO_LARGE"));
        assertThatThrownBy(() -> copy.createFile("bin.dat", "a\0b", Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("BINARY_CONTENT"));
        assertThatThrownBy(() -> copy.createFile(BO, "x", Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("ALREADY_EXISTS"));
    }

    @Test
    void closingRemovesTheCopyAndABadCommitIsRefused() {
        Path root = copy.root();
        copy.close();
        assertThat(Files.exists(root)).isFalse();
        assertThatThrownBy(() -> new ScratchCopies(tmp.resolve("work").toString()).open("x", source, "0".repeat(40))).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("EXPORT_FAILED"));
        assertThatThrownBy(() -> new ScratchCopies(tmp.resolve("work").toString()).open("x", source, "not-a-sha")).isInstanceOf(EditRejected.class);
    }

    @Test
    void aStoredPatchReappliesToAFreshCopyOfTheSameCommitAndNothingElse() {
        copy.edit(BO, "videosFor", "videosForRole", Set.of());
        copy.createFile("docs/new.md", "hi\n", Set.of());
        var diff = copy.diff();
        try (WorkCopy fresh = new ScratchCopies(tmp.resolve("work").toString()).open("again", source, sha)) {
            fresh.applyPatch(diff.patch(), Set.of());
            assertThat(fresh.diff().patch()).isEqualTo(diff.patch());
            assertThatThrownBy(() -> fresh.applyPatch(diff.patch(), Set.of())).as("applying twice does not apply").isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("PATCH_DOES_NOT_APPLY"));
        }
    }

    @Test
    void aPatchTouchingSecretsOrUnapprovedBuildFilesIsRefusedWholesale() {
        String patch = "diff --git a/.env b/.env\n--- a/.env\n+++ b/.env\n@@ -1 +1 @@\n-SECRET=1\n+SECRET=2\n";
        assertThatThrownBy(() -> copy.applyPatch(patch, Set.of(".env"))).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("RESTRICTED_PATH"));
        String gradle = "diff --git a/build.gradle b/build.gradle\n--- a/build.gradle\n+++ b/build.gradle\n@@ -1 +1 @@\n-plugins { }\n+plugins { id 'x' }\n";
        assertThatThrownBy(() -> copy.applyPatch(gradle, Set.of())).isInstanceOfSatisfying(EditRejected.class, e -> assertThat(e.code()).isEqualTo("PROTECTED_PATH"));
        assertThatThrownBy(() -> copy.applyPatch("not a diff at all", Set.of())).isInstanceOf(EditRejected.class);
        assertThat(copy.diff().isEmpty()).isTrue();
    }
}
