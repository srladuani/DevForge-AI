package ai.devforge.execution;

import java.util.List;

/**
 * What actually happened. NOT_RUN means nothing was verified (and says why); it is never reported as a pass.
 *
 * @param command what was run for the test step (empty when nothing ran)
 * @param tests   -1 when no test report was found
 */
public record ValidationReport(Status status, String command, int tests, int failures, int errors, int skipped, List<String> failingTests, String note, String outputTail) {
    public enum Status { PASSED, FAILED, NOT_RUN }

    public static ValidationReport notRun(String note) { return new ValidationReport(Status.NOT_RUN, "", -1, 0, 0, 0, List.of(), note, ""); }
}
