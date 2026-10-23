package ai.devforge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devforge.IntegrationTest;
import ai.devforge.platform.DomainException;
import ai.devforge.testsupport.FixtureRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class RepositoryModuleTest extends IntegrationTest {
    @Autowired Repositories repositories;
    @Autowired RepoReader reader;
    @Autowired EvidenceVerifier verifier;

    @TempDir Path tmp;

    // ---- profile detection
    @Test
    void detectsAGradleSpringBootProjectWithToolchain() {
        var repo = repositories.registerLocal(FixtureRepos.gradleSpring(tmp.resolve("gradle")).toString(), "acme-backend");
        var gradle = repo.profile().buildTools().stream().filter(b -> b.tool().equals("gradle")).findFirst().orElseThrow();
        assertThat(gradle.frameworks()).contains("spring-boot");
        assertThat(gradle.toolchain()).contains("java 21").contains("gradle 9.5.1");
        assertThat(gradle.testCommand()).isEqualTo("./gradlew test");
        assertThat(repo.profile().languages()).containsKey("Java");
        assertThat(repo.profile().instructionFiles()).contains("CLAUDE.md");
        assertThat(repo.defaultBranch()).isEqualTo("main");
    }

    @Test
    void detectsMavenWebpackAndViteProjects() {
        var maven = repositories.registerLocal(FixtureRepos.mavenSpring(tmp.resolve("maven")).toString(), null).profile().buildTools().getFirst();
        assertThat(maven.tool()).isEqualTo("maven");
        assertThat(maven.toolchain()).isEqualTo("java 17");
        assertThat(maven.testCommand()).isEqualTo("./mvnw -B test");

        var webpack = repositories.registerLocal(FixtureRepos.reactWebpack(tmp.resolve("webpack")).toString(), null).profile().buildTools().getFirst();
        assertThat(webpack.tool()).isEqualTo("yarn");
        assertThat(webpack.frameworks()).contains("react", "webpack", "antd", "test:karma");
        assertThat(webpack.toolchain()).isEqualTo("node >=20");
        assertThat(webpack.testCommand()).isEqualTo("yarn test");

        var vite = repositories.registerLocal(FixtureRepos.reactVite(tmp.resolve("vite")).toString(), null).profile().buildTools().getFirst();
        assertThat(vite.tool()).isEqualTo("npm");
        assertThat(vite.frameworks()).contains("react", "vite", "typescript", "test:vitest");
        assertThat(vite.testCommand()).isEqualTo("npm test");
        assertThat(vite.buildCommand()).isEqualTo("npm run build");
    }

    @Test
    void unknownStackIsReportedHonestlyNotGuessed() {
        var p = repositories.registerLocal(FixtureRepos.commit(tmp.resolve("plain"), Map.of("main.c", "int main(){}\n")).toString(), null).profile();
        assertThat(p.buildTools()).isEmpty();
        assertThat(p.notes()).anyMatch(n -> n.contains("No known build tool"));
    }

    // ---- registration guards
    @Test
    void refusesPathsOutsideAllowedRootsAndNonGitDirectories() throws Exception {
        assertThatThrownBy(() -> repositories.registerLocal("/usr", null)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REPOSITORY_PATH_NOT_ALLOWED"));
        Path notGit = Files.createDirectories(tmp.resolve("notgit"));
        assertThatThrownBy(() -> repositories.registerLocal(notGit.toString(), null)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_A_GIT_REPOSITORY"));
        assertThatThrownBy(() -> repositories.registerLocal(tmp.resolve("missing").toString(), null)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REPOSITORY_NOT_FOUND"));
    }

    @Test
    void symlinkEscapingTheAllowedRootIsRefused() throws Exception {
        Path link = tmp.resolve("sneaky");
        Files.createSymbolicLink(link, Path.of("/usr"));
        assertThatThrownBy(() -> repositories.registerLocal(link.toString(), null)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REPOSITORY_PATH_NOT_ALLOWED"));
    }

    @Test
    void registeringTheSamePathTwiceReusesTheRepository() {
        Path p = FixtureRepos.mavenSpring(tmp.resolve("twice"));
        assertThat(repositories.registerLocal(p.toString(), null).id()).isEqualTo(repositories.registerLocal(p.toString(), "other name").id());
    }

    // ---- reading, search, denylist
    @Test
    void rankingFindsTheRelevantClassesFromTicketLikeKeywords() {
        var repo = repositories.registerLocal(FixtureRepos.gradleSpring(tmp.resolve("rank")).toString(), null);
        var hits = reader.rankFiles(repo.id(), repo.headSha(), List.of("navigation", "video", "role", "TrainingVideo"), "src/", List.of(".java"), 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.stream().map(SearchHit::path).limit(2)).anyMatch(p -> p.endsWith("TrainingVideoBO.java"));
        assertThat(hits.stream().map(SearchHit::path)).noneMatch(p -> p.contains("InvoiceService"));
    }

    @Test
    void productionCodeOutranksATestThatMentionsTheSameWords() {
        Path dir = FixtureRepos.gradleSpring(tmp.resolve("tests-rank"));
        FixtureRepos.commitMore(dir, Map.of("src/test/java/com/acme/video/TrainingVideoBOTest.java",
            "package com.acme.video;\n// navigation video role video role navigation video role TrainingVideo navigation\nclass TrainingVideoBOTest { void navigationVideoRole() { videosFor(\"role\"); } }\n"), "add test");
        var repo = repositories.refresh(repositories.registerLocal(dir.toString(), null).id());
        var hits = reader.rankFiles(repo.id(), repo.headSha(), List.of("navigation", "video", "role", "TrainingVideo"), "src/", List.of(".java"), 5);
        var paths = hits.stream().map(SearchHit::path).toList();
        assertThat(paths).anyMatch(p -> p.endsWith("TrainingVideoBO.java")).anyMatch(p -> p.endsWith("TrainingVideoBOTest.java"));
        assertThat(paths.indexOf("src/main/java/com/acme/video/TrainingVideoBO.java")).isLessThan(paths.indexOf("src/test/java/com/acme/video/TrainingVideoBOTest.java"));
    }

    @Test
    void sensitiveFilesAreNeverListedSearchedReadOrVerified() {
        var repo = repositories.registerLocal(FixtureRepos.gradleSpring(tmp.resolve("deny")).toString(), null);
        assertThat(reader.listFiles(repo.id(), repo.headSha(), null, null)).noneMatch(f -> f.equals(".env") || f.contains("application-prod"));
        assertThat(reader.grepLines(repo.id(), repo.headSha(), "super-secret-password", null, 20)).isEmpty();
        assertThat(reader.grepLines(repo.id(), repo.headSha(), "prod-secret", null, 20)).isEmpty();
        assertThatThrownBy(() -> reader.read(repo.id(), repo.headSha(), ".env", 1, 1)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("PATH_RESTRICTED"));
        var v = verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), ".env", 1, 1));
        assertThat(v.verified()).isFalse();
        assertThat(repo.profile().instructionFiles()).doesNotContain(".env");
    }

    @Test
    void pathTraversalIsRejected() {
        var repo = repositories.registerLocal(FixtureRepos.mavenSpring(tmp.resolve("trav")).toString(), null);
        for (String bad : List.of("../etc/passwd", "/etc/passwd", "src/../../x", "a\\b", "src//App.java")) {
            assertThatThrownBy(() -> reader.read(repo.id(), repo.headSha(), bad, 1, 1)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_PATH"));
        }
        assertThat(verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), "../../etc/passwd", 1, 1)).verified()).isFalse();
    }

    @Test
    void searchTermsCannotInjectGitOptions() {
        var repo = repositories.registerLocal(FixtureRepos.mavenSpring(tmp.resolve("inj")).toString(), null);
        assertThatThrownBy(() -> reader.grepLines(repo.id(), repo.headSha(), "--open-files-in-pager=touch /tmp/pwned", null, 5))
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_SEARCH_TERM"));
        assertThatThrownBy(() -> reader.read(repo.id(), "--output=/tmp/x", "pom.xml", 1, 1)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_SHA"));
    }

    // ---- evidence
    @Test
    void evidenceIsVerifiedAgainstTheRealFileAtTheCitedCommit() {
        var repo = repositories.registerLocal(FixtureRepos.gradleSpring(tmp.resolve("ev")).toString(), null);
        String path = "src/main/java/com/acme/video/TrainingVideoBO.java";
        var ok = verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), path, 6, 7));
        assertThat(ok.verified()).isTrue();
        assertThat(ok.excerpt()).contains("returns every navigation video");
        assertThat(ok.contentHash()).startsWith("sha256:");
        assertThat(verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), path, 6, 7)).contentHash()).isEqualTo(ok.contentHash());

        assertThat(verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), path, 6, 999)).problem()).contains("past the end");
        assertThat(verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), "src/main/java/Nope.java", 1, 1)).problem()).contains("does not exist");
        assertThat(verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), path, 0, 3)).verified()).isFalse();
        assertThat(verifier.verify(new EvidenceRef(repo.id(), "deadbeefdeadbeef", path, 1, 1)).verified()).isFalse();
    }

    @Test
    void readingIsPinnedToTheShaEvenAfterNewCommits() {
        Path dir = FixtureRepos.gradleSpring(tmp.resolve("pin"));
        var repo = repositories.registerLocal(dir.toString(), null);
        String oldSha = repo.headSha();
        String path = "src/main/java/com/acme/video/TrainingVideoBO.java";
        FixtureRepos.commitMore(dir, Map.of(path, "package com.acme.video;\npublic class TrainingVideoBO { /* rewritten */ }\n"), "rewrite");
        assertThat(reader.read(repo.id(), oldSha, path, 6, 7)).contains("returns every navigation video");
        var refreshed = repositories.refresh(repo.id());
        assertThat(refreshed.headSha()).isNotEqualTo(oldSha);
        assertThat(reader.read(repo.id(), refreshed.headSha(), path, 2, 2)).contains("rewritten");
        assertThat(verifier.verify(new EvidenceRef(repo.id(), oldSha, path, 6, 7)).verified()).as("old evidence stays valid at its own commit").isTrue();
    }

    @Test
    void lineCountsAgreeBetweenReadAndVerifyAtTheEndOfAFile() {
        var repo = repositories.registerLocal(FixtureRepos.gradleSpring(tmp.resolve("eof")).toString(), null);
        String path = "src/main/java/com/acme/billing/InvoiceService.java"; // two lines, ends with a newline
        assertThat(reader.read(repo.id(), repo.headSha(), path, 1, 50).lines().count()).as("no phantom line after the final newline").isEqualTo(2);
        assertThatThrownBy(() -> reader.read(repo.id(), repo.headSha(), path, 3, 4)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_RANGE"));
        assertThat(verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), path, 1, 2)).verified()).isTrue();
        assertThat(verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), path, 1, 3)).verified()).isFalse();
    }

    @Test
    void readingNeverModifiesTheCheckout() throws Exception {
        Path dir = FixtureRepos.gradleSpring(tmp.resolve("ro"));
        Path index = dir.resolve(".git/index");
        String statusBefore = FixtureRepos.git(dir, "status", "--porcelain"); // may refresh the index itself, so measure after it
        long before = Files.getLastModifiedTime(index).toMillis();
        Thread.sleep(1100);
        var repo = repositories.registerLocal(dir.toString(), null);
        reader.rankFiles(repo.id(), repo.headSha(), List.of("video"), "src/", List.of(".java"), 5);
        reader.grepLines(repo.id(), repo.headSha(), "video", null, 10);
        reader.read(repo.id(), repo.headSha(), "src/main/java/com/acme/video/TrainingVideoBO.java", 1, 5);
        verifier.verify(new EvidenceRef(repo.id(), repo.headSha(), "src/main/java/com/acme/video/TrainingVideoBO.java", 1, 2));
        assertThat(Files.getLastModifiedTime(index).toMillis()).isEqualTo(before);
        assertThat(FixtureRepos.git(dir, "status", "--porcelain")).isEqualTo(statusBefore);
    }
}
