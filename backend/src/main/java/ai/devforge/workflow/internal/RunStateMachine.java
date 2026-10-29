package ai.devforge.workflow.internal;

import ai.devforge.platform.DomainException;
import ai.devforge.workflow.RunStatus;
import static ai.devforge.workflow.RunStatus.*;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only definition of which run status may follow which (docs/architecture/workflow-state-machine.md). Pure and Spring-free so it
 * can be tested exhaustively. Status is never assigned anywhere else; guards (approval hash, ownership) live in the service.
 */
final class RunStateMachine {
    /** States a worker thread acts in. Entering one enqueues a job. */
    static final Set<RunStatus> WORKER_STATES = EnumSet.of(INPUT_RETRIEVAL, CONTEXT_GATHERING, INVESTIGATION, PROPOSING_FIX, IMPLEMENTATION, VALIDATION, PR_PREPARATION);

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = new EnumMap<>(RunStatus.class);

    static {
        allow(CREATED, INPUT_RETRIEVAL, CANCELLED, FAILED);
        allow(INPUT_RETRIEVAL, AWAITING_INPUT, CONTEXT_GATHERING, FAILED, CANCELLED);
        // AWAITING_INPUT resumes into the step that paused (clarification re-runs retrieval; a fixed credential re-enters the LLM step)
        allow(AWAITING_INPUT, INPUT_RETRIEVAL, CONTEXT_GATHERING, INVESTIGATION, PROPOSING_FIX, IMPLEMENTATION, FAILED, CANCELLED);
        allow(CONTEXT_GATHERING, INVESTIGATION, AWAITING_INPUT, FAILED, CANCELLED);
        allow(INVESTIGATION, FINDINGS_AVAILABLE, AWAITING_INPUT, FAILED, CANCELLED);
        allow(FINDINGS_AVAILABLE, COMPLETED, PROPOSING_FIX, INVESTIGATION, CANCELLED);
        allow(PROPOSING_FIX, FIX_PROPOSAL_AVAILABLE, AWAITING_INPUT, FAILED, CANCELLED);
        allow(FIX_PROPOSAL_AVAILABLE, AWAITING_APPROVAL, COMPLETED, PROPOSING_FIX, CANCELLED);
        allow(AWAITING_APPROVAL, IMPLEMENTATION, FIX_PROPOSAL_AVAILABLE, PR_PREPARATION, AWAITING_REVIEW, CANCELLED, FAILED);
        allow(IMPLEMENTATION, VALIDATION, AWAITING_INPUT, FAILED, CANCELLED);
        allow(VALIDATION, IMPLEMENTATION, AWAITING_REVIEW, FAILED, CANCELLED);
        allow(AWAITING_REVIEW, AWAITING_APPROVAL, IMPLEMENTATION, COMPLETED, CANCELLED);
        allow(PR_PREPARATION, COMPLETED, FAILED, CANCELLED);
        // A retryable failure may be resumed into the step that failed; COMPLETED and CANCELLED are final.
        allow(FAILED, INPUT_RETRIEVAL, CONTEXT_GATHERING, INVESTIGATION, PROPOSING_FIX, IMPLEMENTATION, VALIDATION, PR_PREPARATION, CANCELLED);
        allow(COMPLETED);
        allow(CANCELLED);
    }

    private RunStateMachine() {}

    private static void allow(RunStatus from, RunStatus... to) {
        ALLOWED.put(from, to.length == 0 ? EnumSet.noneOf(RunStatus.class) : EnumSet.copyOf(Set.of(to)));
    }

    static boolean canTransition(RunStatus from, RunStatus to) { return ALLOWED.get(from).contains(to); }

    static Set<RunStatus> next(RunStatus from) { return Set.copyOf(ALLOWED.get(from)); }

    static void require(RunStatus from, RunStatus to) {
        if (!canTransition(from, to)) throw DomainException.conflict("ILLEGAL_TRANSITION", "A run cannot move from " + from + " to " + to);
    }

    static boolean isFinal(RunStatus s) { return s == COMPLETED || s == CANCELLED; }

    /** Not finished: includes FAILED, which can still be resumed if it was retryable. */
    static boolean isActive(RunStatus s) { return !isFinal(s) && s != FAILED; }

    static boolean isWorkerState(RunStatus s) { return WORKER_STATES.contains(s); }
}
