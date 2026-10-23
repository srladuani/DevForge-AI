package ai.devforge.workflow.internal;

import ai.devforge.workflow.RunStatus;

/** A requested change of run status together with the bookkeeping columns that go with it (all cleared unless set). */
record Move(RunStatus to, String awaitReason, String resumeState, String failureCode, String failureStep, String failureMessage, Boolean failureRetryable) {
    static Move to(RunStatus to) { return new Move(to, null, null, null, null, null, null); }

    static Move await(String reason, RunStatus resumeState) { return new Move(RunStatus.AWAITING_INPUT, reason, resumeState.name(), null, null, null, null); }

    static Move awaitApproval(RunStatus resumeState) { return new Move(RunStatus.AWAITING_APPROVAL, "APPROVAL", resumeState.name(), null, null, null, null); }

    static Move failed(String code, String step, String message, boolean retryable) { return new Move(RunStatus.FAILED, null, null, code, step, message, retryable); }
}
