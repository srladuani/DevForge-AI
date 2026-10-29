package ai.devforge.workflow.internal;

import ai.devforge.workflow.RunStatus;

/** What a step handler decided. The engine, not the handler, applies it. */
record Outcome(Move move, String stepStatus, String detail) {
    static Outcome next(RunStatus to, String detail) { return new Outcome(Move.to(to), "SUCCEEDED", detail); }

    static Outcome await(String reason, RunStatus resume, String detail) { return new Outcome(Move.await(reason, resume), "PAUSED", detail); }

    static Outcome fail(String code, RunStatus step, String message, boolean retryable) { return new Outcome(Move.failed(code, step.name(), message, retryable), "FAILED", message); }
}
