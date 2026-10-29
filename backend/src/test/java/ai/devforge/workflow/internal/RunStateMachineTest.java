package ai.devforge.workflow.internal;

import static ai.devforge.workflow.RunStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devforge.platform.DomainException;
import ai.devforge.workflow.RunStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunStateMachineTest {
    /** The documented table, written independently of the implementation. Every pair not listed here must be illegal. */
    private static final List<RunStatus[]> LEGAL = List.of(
        new RunStatus[] {CREATED, INPUT_RETRIEVAL}, new RunStatus[] {CREATED, CANCELLED}, new RunStatus[] {CREATED, FAILED},
        new RunStatus[] {INPUT_RETRIEVAL, AWAITING_INPUT}, new RunStatus[] {INPUT_RETRIEVAL, CONTEXT_GATHERING}, new RunStatus[] {INPUT_RETRIEVAL, FAILED}, new RunStatus[] {INPUT_RETRIEVAL, CANCELLED},
        new RunStatus[] {AWAITING_INPUT, INPUT_RETRIEVAL}, new RunStatus[] {AWAITING_INPUT, CONTEXT_GATHERING}, new RunStatus[] {AWAITING_INPUT, INVESTIGATION}, new RunStatus[] {AWAITING_INPUT, PROPOSING_FIX},
        new RunStatus[] {AWAITING_INPUT, IMPLEMENTATION}, new RunStatus[] {AWAITING_INPUT, FAILED}, new RunStatus[] {AWAITING_INPUT, CANCELLED},
        new RunStatus[] {CONTEXT_GATHERING, INVESTIGATION}, new RunStatus[] {CONTEXT_GATHERING, AWAITING_INPUT}, new RunStatus[] {CONTEXT_GATHERING, FAILED}, new RunStatus[] {CONTEXT_GATHERING, CANCELLED},
        new RunStatus[] {INVESTIGATION, FINDINGS_AVAILABLE}, new RunStatus[] {INVESTIGATION, AWAITING_INPUT}, new RunStatus[] {INVESTIGATION, FAILED}, new RunStatus[] {INVESTIGATION, CANCELLED},
        new RunStatus[] {FINDINGS_AVAILABLE, COMPLETED}, new RunStatus[] {FINDINGS_AVAILABLE, PROPOSING_FIX}, new RunStatus[] {FINDINGS_AVAILABLE, INVESTIGATION}, new RunStatus[] {FINDINGS_AVAILABLE, CANCELLED},
        new RunStatus[] {PROPOSING_FIX, FIX_PROPOSAL_AVAILABLE}, new RunStatus[] {PROPOSING_FIX, AWAITING_INPUT}, new RunStatus[] {PROPOSING_FIX, FAILED}, new RunStatus[] {PROPOSING_FIX, CANCELLED},
        new RunStatus[] {FIX_PROPOSAL_AVAILABLE, AWAITING_APPROVAL}, new RunStatus[] {FIX_PROPOSAL_AVAILABLE, COMPLETED}, new RunStatus[] {FIX_PROPOSAL_AVAILABLE, PROPOSING_FIX}, new RunStatus[] {FIX_PROPOSAL_AVAILABLE, CANCELLED},
        new RunStatus[] {AWAITING_APPROVAL, IMPLEMENTATION}, new RunStatus[] {AWAITING_APPROVAL, FIX_PROPOSAL_AVAILABLE}, new RunStatus[] {AWAITING_APPROVAL, PR_PREPARATION}, new RunStatus[] {AWAITING_APPROVAL, AWAITING_REVIEW},
        new RunStatus[] {AWAITING_APPROVAL, CANCELLED}, new RunStatus[] {AWAITING_APPROVAL, FAILED},
        new RunStatus[] {IMPLEMENTATION, VALIDATION}, new RunStatus[] {IMPLEMENTATION, AWAITING_INPUT}, new RunStatus[] {IMPLEMENTATION, FAILED}, new RunStatus[] {IMPLEMENTATION, CANCELLED},
        new RunStatus[] {VALIDATION, IMPLEMENTATION}, new RunStatus[] {VALIDATION, AWAITING_REVIEW}, new RunStatus[] {VALIDATION, FAILED}, new RunStatus[] {VALIDATION, CANCELLED},
        new RunStatus[] {AWAITING_REVIEW, AWAITING_APPROVAL}, new RunStatus[] {AWAITING_REVIEW, IMPLEMENTATION}, new RunStatus[] {AWAITING_REVIEW, COMPLETED}, new RunStatus[] {AWAITING_REVIEW, CANCELLED},
        new RunStatus[] {PR_PREPARATION, COMPLETED}, new RunStatus[] {PR_PREPARATION, FAILED}, new RunStatus[] {PR_PREPARATION, CANCELLED},
        new RunStatus[] {FAILED, INPUT_RETRIEVAL}, new RunStatus[] {FAILED, CONTEXT_GATHERING}, new RunStatus[] {FAILED, INVESTIGATION}, new RunStatus[] {FAILED, PROPOSING_FIX},
        new RunStatus[] {FAILED, IMPLEMENTATION}, new RunStatus[] {FAILED, VALIDATION}, new RunStatus[] {FAILED, PR_PREPARATION}, new RunStatus[] {FAILED, CANCELLED});

    @Test
    void everyDocumentedTransitionIsAllowedAndNothingElseIs() {
        List<String> unexpectedlyAllowed = new ArrayList<>();
        List<String> unexpectedlyDenied = new ArrayList<>();
        for (RunStatus from : RunStatus.values()) {
            for (RunStatus to : RunStatus.values()) {
                boolean documented = LEGAL.stream().anyMatch(p -> p[0] == from && p[1] == to);
                boolean actual = RunStateMachine.canTransition(from, to);
                if (actual && !documented) unexpectedlyAllowed.add(from + "->" + to);
                if (!actual && documented) unexpectedlyDenied.add(from + "->" + to);
            }
        }
        assertThat(unexpectedlyAllowed).as("allowed but not documented").isEmpty();
        assertThat(unexpectedlyDenied).as("documented but denied").isEmpty();
    }

    @Test
    void finalStatesHaveNoExits() {
        assertThat(RunStateMachine.next(COMPLETED)).isEmpty();
        assertThat(RunStateMachine.next(CANCELLED)).isEmpty();
        assertThat(RunStateMachine.isFinal(COMPLETED)).isTrue();
        assertThat(RunStateMachine.isFinal(FAILED)).as("a retryable failure can be resumed").isFalse();
    }

    @Test
    void everyNonFinalStateCanBeCancelledOrCanFail() {
        for (RunStatus s : RunStatus.values()) {
            if (RunStateMachine.isFinal(s)) continue;
            assertThat(RunStateMachine.canTransition(s, CANCELLED)).as(s + " must be cancellable").isTrue();
        }
        for (RunStatus s : RunStateMachine.WORKER_STATES) {
            assertThat(RunStateMachine.canTransition(s, FAILED)).as(s + " must be able to fail").isTrue();
        }
    }

    @Test
    void implementationIsOnlyReachableThroughApprovalReviewValidationOrAResume() {
        Set<RunStatus> into = Set.of(AWAITING_APPROVAL, AWAITING_REVIEW, VALIDATION, AWAITING_INPUT, FAILED);
        for (RunStatus from : RunStatus.values()) {
            if (RunStateMachine.canTransition(from, IMPLEMENTATION)) assertThat(into).contains(from);
        }
        assertThat(RunStateMachine.canTransition(FIX_PROPOSAL_AVAILABLE, IMPLEMENTATION)).as("a proposal alone never authorises edits").isFalse();
        assertThat(RunStateMachine.canTransition(FINDINGS_AVAILABLE, IMPLEMENTATION)).isFalse();
    }

    @Test
    void requireThrowsAConflictWithStableCode() {
        assertThatThrownBy(() -> RunStateMachine.require(COMPLETED, INVESTIGATION)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("ILLEGAL_TRANSITION"));
        RunStateMachine.require(CREATED, INPUT_RETRIEVAL);
    }

    @Test
    void workerStatesAreExactlyTheOnesWhereAWorkerActs() {
        assertThat(RunStateMachine.WORKER_STATES).containsExactlyInAnyOrder(INPUT_RETRIEVAL, CONTEXT_GATHERING, INVESTIGATION, PROPOSING_FIX, IMPLEMENTATION, VALIDATION, PR_PREPARATION);
        assertThat(RunStateMachine.isWorkerState(AWAITING_APPROVAL)).isFalse();
    }
}
