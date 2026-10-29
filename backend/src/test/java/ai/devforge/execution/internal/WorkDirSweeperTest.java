package ai.devforge.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkDirSweeperTest {
    @TempDir Path root;

    private Path dir(String rel, Duration age) throws IOException {
        Path p = Files.createDirectories(root.resolve(rel));
        Files.writeString(p.resolve("f.txt"), "x");
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(age)));
        return p;
    }

    @Test
    void removesStaleWorkingCopiesAndUnusedCachesButKeepsFreshOnesAndNeverLeavesTheWorkDirectory() throws IOException {
        Path stale = dir("run-old", Duration.ofHours(8));
        Path fresh = dir("run-new", Duration.ofMinutes(5));
        Path oldCache = dir("cache/repo-a", Duration.ofDays(20));
        Path usedCache = dir("cache/repo-b", Duration.ofDays(1));
        Path outside = Files.createDirectory(root.resolveSibling("outside-" + System.nanoTime()));
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            Files.setLastModifiedTime(outside, FileTime.from(Instant.now().minus(Duration.ofDays(60))));
            int removed = new WorkDirSweeper(root.toString(), Duration.ofHours(6), Duration.ofDays(14)).sweep(Instant.now());
            assertThat(removed).isEqualTo(2);
            assertThat(stale).doesNotExist();
            assertThat(oldCache).doesNotExist();
            assertThat(fresh).exists();
            assertThat(usedCache).exists();
            assertThat(outside).as("a link out of the work directory is never followed").exists();
            assertThat(root.resolve("cache")).exists();
        } finally {
            Files.deleteIfExists(root.resolve("link"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aMissingWorkDirectoryIsNotAnError() {
        assertThat(new WorkDirSweeper(root.resolve("nope").toString(), Duration.ofHours(1), Duration.ofDays(1)).sweep(Instant.now())).isZero();
    }
}
