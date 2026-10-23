package ai.devforge.execution.internal;

import ai.devforge.execution.WorkCopies;
import ai.devforge.execution.WorkCopy;
import ai.devforge.execution.WorkCopy.EditRejected;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Creates {@link WorkCopy}s under a managed directory (never inside, or next to, a user's checkout). */
@Component
class ScratchCopies implements WorkCopies {
    private final Path root;

    ScratchCopies(@Value("${devforge.execution.work-root:}") String configured) { this.root = rootOf(configured); }

    static Path rootOf(String configured) {
        return (configured == null || configured.isBlank() ? Path.of(System.getProperty("java.io.tmpdir"), "devforge-work") : Path.of(configured)).toAbsolutePath();
    }

    @Override
    public WorkCopy open(String label, Path sourceRepo, String sha) {
        if (!sha.matches("[0-9a-f]{40,64}")) throw new EditRejected("EXPORT_FAILED", "Not a commit id");
        Path dir = null;
        try {
            Files.createDirectories(root);
            dir = Files.createTempDirectory(root, label.replaceAll("[^A-Za-z0-9._-]", "_") + "-");
            export(sourceRepo, sha, dir);
            GitProcess.run(dir, "init", "-q");
            GitProcess.run(dir, "add", "-A");
            // fixed dates: the same export always gives the same baseline commit
            var commit = GitProcess.runWithInput(dir, null, java.util.Map.of("GIT_AUTHOR_DATE", "946684800 +0000", "GIT_COMMITTER_DATE", "946684800 +0000"), "commit", "-q", "--allow-empty", "-m", "baseline " + sha);
            if (commit.exit() != 0) throw new EditRejected("EXPORT_FAILED", "Could not record the baseline");
            return new GitWorkCopy(dir);
        } catch (IOException | RuntimeException e) {
            if (dir != null) GitWorkCopy.deleteTree(dir);
            if (e instanceof EditRejected r) throw r;
            throw new EditRejected("EXPORT_FAILED", "Could not prepare a working copy of the repository");
        }
    }

    @Override
    public WorkCopy openWithHistory(String label, Path sourceRepo, String sha) {
        if (!sha.matches("[0-9a-f]{40,64}")) throw new EditRejected("EXPORT_FAILED", "Not a commit id");
        Path dir = null;
        try {
            Files.createDirectories(root);
            dir = Files.createTempDirectory(root, label.replaceAll("[^A-Za-z0-9._-]", "_") + "-");
            if (GitProcess.run(dir, "clone", "-q", "--no-checkout", "--no-hardlinks", "--", sourceRepo.toString(), dir.toString()).exit() != 0) throw new EditRejected("EXPORT_FAILED", "Could not read the repository");
            if (GitProcess.run(dir, "checkout", "-q", "--detach", sha).exit() != 0) throw new EditRejected("EXPORT_FAILED", "The pinned commit is not in the repository");
            GitProcess.run(dir, "remote", "remove", "origin");
            return new GitWorkCopy(dir);
        } catch (IOException | RuntimeException e) {
            if (dir != null) GitWorkCopy.deleteTree(dir);
            if (e instanceof EditRejected r) throw r;
            throw new EditRejected("EXPORT_FAILED", "Could not prepare a working copy of the repository");
        }
    }

    @Override
    public Path cacheDir(String key) { return root.resolve("cache").resolve(key.replaceAll("[^A-Za-z0-9._-]", "_")); }

    /** {@code git archive | tar -x}: reads the source's objects, writes only into {@code dest}. */
    private static void export(Path source, String sha, Path dest) throws IOException {
        ProcessBuilder archive = new ProcessBuilder("git", "-C", source.toString(), "archive", "--format=tar", sha);
        ProcessBuilder untar = new ProcessBuilder("tar", "--no-same-owner", "--no-same-permissions", "-x", "-C", dest.toString());
        for (ProcessBuilder pb : List.of(archive, untar)) {
            pb.environment().clear();
            pb.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        }
        archive.redirectError(ProcessBuilder.Redirect.DISCARD);
        untar.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        List<Process> ps = ProcessBuilder.startPipeline(List.of(archive, untar));
        try {
            for (Process p : ps) {
                if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    ps.forEach(Process::destroyForcibly);
                    throw new IOException("export timed out");
                }
                if (p.exitValue() != 0) throw new IOException("export failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ps.forEach(Process::destroyForcibly);
            throw new IOException("export interrupted");
        }
    }
}
