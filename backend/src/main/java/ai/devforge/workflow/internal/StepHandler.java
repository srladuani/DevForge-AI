package ai.devforge.workflow.internal;

import ai.devforge.workflow.RunStatus;
import java.util.function.BooleanSupplier;

/** One worker-side step of a workflow. Handlers do the work and return a decision; they never change run status themselves. */
interface StepHandler {
    RunStatus step();

    /** Must be safe to run again for the same run (a crashed worker's job is re-executed). */
    Outcome handle(RunRow run, StepContext ctx);

    /** @param attempt how many times this step has been tried for this run, counting this one; @param cancelRequested poll between tool calls */
    record StepContext(int attempt, BooleanSupplier cancelRequested) {}
}
