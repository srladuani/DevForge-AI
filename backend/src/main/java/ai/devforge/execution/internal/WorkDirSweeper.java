package ai.devforge.execution.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes what crashed or abandoned work leaves behind: private working copies older than a few hours (a healthy one lives for minutes and
 * deletes itself) and dependency caches nobody has used for weeks. Only ever touches the managed work directory.
 */
@Component
@EnableScheduling
class WorkDirSweeper {
    private static final Logger log = LoggerFactory.getLogger(WorkDirSweeper.class);
    private final Path root;
    private final Duration workTtl;
    private final Duration cacheTtl;

    WorkDirSweeper(@Value("${devforge.execution.work-root:}") String configured, @Value("${devforge.execution.work-ttl:PT6H}") Duration workTtl,
                   @Value("${devforge.execution.cache-ttl:P14D}") Duration cacheTtl) {
        this.root = ScratchCopies.rootOf(configured);
        this.workTtl = workTtl;
        this.cacheTtl = cacheTtl;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    void scheduled() { sweep(Instant.now()); }

    /** @return how many directories were removed */
    int sweep(Instant now) {
        if (!Files.isDirectory(root)) return 0;
        int removed = 0;
        try (Stream<Path> children = Files.list(root)) {
            for (Path p : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(p) || Files.isSymbolicLink(p)) continue;
                if (p.getFileName().toString().equals("cache")) removed += sweepCaches(p, now);
                else if (older(p, now, workTtl)) {
                    GitWorkCopy.deleteTree(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not sweep the work directory: {}", e.getMessage());
        }
        if (removed > 0) log.info("Work directory sweep removed {} stale director(ies)", removed);
        return removed;
    }

    private int sweepCaches(Path cache, Instant now) throws IOException {
        int removed = 0;
        try (Stream<Path> kids = Files.list(cache)) {
            for (Path p : (Iterable<Path>) kids::iterator) {
                if (Files.isDirectory(p) && !Files.isSymbolicLink(p) && older(p, now, cacheTtl)) {
                    GitWorkCopy.deleteTree(p);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static boolean older(Path p, Instant now, Duration ttl) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isBefore(now.minus(ttl));
        } catch (IOException e) {
            return false;
        }
    }
}
