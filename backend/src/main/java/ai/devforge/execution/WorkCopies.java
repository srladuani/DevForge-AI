package ai.devforge.execution;

import java.nio.file.Path;

public interface WorkCopies {
    /**
     * Exports {@code sha} of the repository at {@code sourceRepo} into a new private directory. Reads the source's git objects only.
     *
     * @throws SandboxUnavailableException never; failures are {@link WorkCopy.EditRejected} with code EXPORT_FAILED
     */
    WorkCopy open(String label, Path sourceRepo, String sha);

    /** A directory that keeps downloaded dependencies for one repository between validations. */
    Path cacheDir(String key);
}
