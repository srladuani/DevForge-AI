package ai.devforge.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A private, disposable copy of one repository at one commit, where a run's edits are made. The user's checkout is never written to
 * (the copy is exported with {@code git archive}). Every operation is path-jailed; secrets-by-name are unreachable.
 */
public interface WorkCopy extends AutoCloseable {
    record Diff(String patch, List<String> files) {
        public boolean isEmpty() { return files.isEmpty(); }
    }

    /** A rejected edit; {@code code} is stable for the caller to act on. */
    final class EditRejected extends RuntimeException {
        private final String code;

        public EditRejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    /** The only writable place; the sandbox mounts this as /work. */
    Path root();

    String read(String relativePath);

    /**
     * Replaces {@code oldText} with {@code newText}; {@code oldText} must occur exactly once.
     *
     * @param approvedProtected protected files (build scripts, CI, lockfiles) the approved proposal named; any other protected file is refused
     */
    void edit(String relativePath, String oldText, String newText, Set<String> approvedProtected);

    void createFile(String relativePath, String content, Set<String> approvedProtected);

    /**
     * Applies a unified diff produced earlier (by {@link #diff()} on a copy of the same commit). Every file it touches goes through the same
     * path rules as an edit; a patch that does not apply cleanly changes nothing.
     */
    void applyPatch(String patch, Set<String> approvedProtected);

    /** Everything changed since the export, as a unified diff. */
    Diff diff();

    @Override
    void close();
}
