package ai.devforge.execution;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public interface Validator {
    /**
     * Prepares dependencies (the only step with network, from lockfiles) then runs the tests offline inside the sandbox, against the
     * edits already in {@code copy}. Never throws for a build or test failure; that is the report.
     *
     * @param cacheDir per-repository directory that keeps downloaded dependencies between runs
     */
    ValidationReport validate(WorkCopy copy, ValidationRequest request, Path cacheDir, BooleanSupplier cancelled);
}
