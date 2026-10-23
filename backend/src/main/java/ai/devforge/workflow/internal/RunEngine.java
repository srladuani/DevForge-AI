package ai.devforge.workflow.internal;

import ai.devforge.agent.LlmException;
import ai.devforge.approval.ApprovalKind;
import ai.devforge.approval.Approvals;
import ai.devforge.audit.AuditLog;
import ai.devforge.platform.DomainException;
import ai.devforge.workflow.RunMode;
import ai.devforge.workflow.RunStatus;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one workflow step and applies the handler's decision. This is the only place run status changes during execution: every change is a
 * compare-and-set through {@link RunStateMachine}, writes an event, and (for worker states) enqueues the next job in the same transaction.
 */
@Component
class RunEngine {
    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);

    private final RunStore store;
    private final RunDataStore data;
    private final JobQueue queue;
    private final Map<RunStatus, StepHandler> handlers = new EnumMap<>(RunStatus.class);
    private final Approvals approvals;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final WorkflowProperties props;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    RunEngine(RunStore store, RunDataStore data, JobQueue queue, List<StepHandler> handlerList, Approvals approvals, AuditLog audit, TransactionTemplate tx, WorkflowProperties props, io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.store = store;
        this.data = data;
        this.queue = queue;
        handlerList.forEach(h -> handlers.put(h.step(), h));
        this.approvals = approvals;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
    }

    /** Executes the step a job asks for. Safe to call again for the same job (stale jobs are ignored; steps replace their own output). */
    void execute(UUID runId, String stepName) {
        MDC.put("runId", runId.toString());
        MDC.put("step", stepName);
        try {
            RunRow run = store.get(runId);
            RunStatus step = RunStatus.valueOf(stepName);
            if (run.status() == RunStatus.CREATED && step == RunStatus.INPUT_RETRIEVAL) {
                if (!advance(runId, RunStatus.CREATED, Move.to(RunStatus.INPUT_RETRIEVAL), "picked up by a worker")) return;
                run = store.get(runId);
            }
            if (run.status() != step) {
                log.info("Ignoring stale job: run is {} but job is for {}", run.status(), step);
                return;
            }
            if (run.cancelRequested()) {
                advance(runId, step, Move.to(RunStatus.CANCELLED), "cancel requested");
                return;
            }
            StepHandler handler = handlers.get(step);
            if (handler == null) {
                advance(runId, step, Move.failed("NO_HANDLER", stepName, "No handler is registered for " + stepName, false), "configuration error");
                return;
            }
            store.interruptStaleSteps(runId, stepName);
            int base = store.countSteps(runId, stepName);
            UUID stepId = store.startStep(runId, stepName, base + 1);
            long startedNanos = System.nanoTime();
            Outcome outcome = runWithRetries(runId, handler, base);
            meters.timer("devforge.step.duration", "step", stepName, "outcome", outcome.stepStatus()).record(System.nanoTime() - startedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            RunRow after = store.get(runId);
            if (after.cancelRequested()) {
                store.finishStep(runId, stepId, stepName, "CANCELLED", "cancelled while running");
                advance(runId, step, Move.to(RunStatus.CANCELLED), "cancel requested");
                return;
            }
            store.finishStep(runId, stepId, stepName, outcome.stepStatus(), outcome.detail());
            if (!advance(runId, step, outcome.move(), outcome.detail())) log.info("Run changed while step {} was running; outcome dropped", stepName);
        } finally {
            MDC.remove("runId");
            MDC.remove("step");
        }
    }

    /** Last resort for a step that blew up outside the handler contract. */
    void failUnexpected(UUID runId, String stepName, Throwable t) {
        log.error("Step {} crashed for run {}", stepName, runId, t);
        try {
            RunRow run = store.get(runId);
            if (RunStateMachine.canTransition(run.status(), RunStatus.FAILED)) {
                advance(runId, run.status(), Move.failed("INTERNAL_ERROR", stepName, "Unexpected error in " + stepName, true), "unexpected error");
            }
        } catch (RuntimeException e) {
            log.error("Could not record failure for run {}", runId, e);
        }
    }

    private Outcome runWithRetries(UUID runId, StepHandler handler, int base) {
        RunStatus step = handler.step();
        for (int attempt = 1; ; attempt++) {
            try {
                return handler.handle(store.get(runId), new StepHandler.StepContext(base + attempt, () -> store.cancelRequested(runId)));
            } catch (LlmException e) {
                switch (e.kind()) {
                    case AUTH_INVALID, QUOTA_EXHAUSTED -> {
                        // Never retried, never switched to another key: the owner fixes their credential and resumes.
                        store.appendEvent(runId, "NEEDS_INPUT", Map.of("reason", "CREDENTIAL", "problem", e.kind().name(), "message", e.getMessage()));
                        return Outcome.await("CREDENTIAL", step, e.kind() + ": " + e.getMessage());
                    }
                    case RATE_LIMITED, TIMEOUT, UNAVAILABLE -> {
                        if (attempt >= props.maxModelAttempts()) return Outcome.fail("MODEL_" + e.kind(), step, e.getMessage() + " (gave up after " + attempt + " attempts)", true);
                        store.appendEvent(runId, "RETRYING", Map.of("step", step.name(), "attempt", attempt, "reason", e.kind().name()));
                        sleep(props.retryBackoffMillis() * attempt);
                    }
                    default -> { return Outcome.fail("MODEL_" + e.kind(), step, e.getMessage(), false); }
                }
            } catch (DomainException e) {
                boolean environmental = e.code().startsWith("REPOSITORY_") || e.code().startsWith("GIT_") || e.code().startsWith("AGENT_");
                return Outcome.fail(e.code(), step, e.getMessage(), environmental);
            } catch (RuntimeException e) {
                log.error("Step {} failed unexpectedly", step, e);
                return Outcome.fail("INTERNAL_ERROR", step, "Unexpected error in " + step, true);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Applies one status change atomically. Returns false if the run was no longer in {@code from}. */
    boolean advance(UUID runId, RunStatus from, Move move, String reason) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            RunStateMachine.require(from, move.to());
            if (!store.move(runId, from, move)) return false;
            entered(runId, from, move, reason);
            return true;
        }));
    }

    private void entered(UUID runId, RunStatus from, Move move, String reason) {
        RunStatus to = move.to();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.name());
        payload.put("to", to.name());
        if (reason != null) payload.put("reason", reason);
        if (move.awaitReason() != null) payload.put("awaiting", move.awaitReason());
        if (move.failureCode() != null) payload.put("failureCode", move.failureCode());
        store.appendEvent(runId, "STATE_CHANGED", payload);
        if (RunStateMachine.isWorkerState(to)) queue.enqueue(runId, to.name());
        if (RunStateMachine.isFinal(to) || to == RunStatus.FAILED) {
            approvals.supersedePending(runId, "run " + to);
            audit.record("system", "RUN_" + to, "run", runId.toString(), move.failureCode());
        }
        RunRow run = store.get(runId);
        // Automatic hand-offs apply only when arriving from the step that produced the result, never when a human sent the run back
        // (otherwise a rejected approval would instantly be re-requested).
        if (to == RunStatus.FINDINGS_AVAILABLE && from == RunStatus.INVESTIGATION && run.mode() != RunMode.INVESTIGATE_ONLY) {
            cascade(runId, to, Move.to(RunStatus.PROPOSING_FIX), "mode " + run.mode() + " continues without waiting");
        } else if (to == RunStatus.FIX_PROPOSAL_AVAILABLE && from == RunStatus.PROPOSING_FIX && run.mode() == RunMode.FIX_AND_PATCH) {
            requestImplementationApproval(run);
            cascade(runId, to, Move.awaitApproval(RunStatus.FIX_PROPOSAL_AVAILABLE), "implementation needs a human approval");
        }
    }

    private void cascade(UUID runId, RunStatus from, Move next, String reason) {
        RunStateMachine.require(from, next.to());
        if (!store.move(runId, from, next)) throw new IllegalStateException("Run changed during an automatic transition");
        entered(runId, from, next, reason);
    }

    /** Opens the IMPLEMENT approval, bound to the hash of the proposal exactly as stored. */
    void requestImplementationApproval(RunRow run) {
        var proposal = data.currentArtifact(run.id(), "FIX_PROPOSAL").orElseThrow(() -> DomainException.conflict("NO_PROPOSAL", "There is no fix proposal to approve"));
        Map<String, Object> summary = new LinkedHashMap<>();
        var p = store.json.readValue(proposal.content(), Map.class);
        summary.put("filesToChange", p.get("filesToChange"));
        summary.put("commands", p.get("commands"));
        summary.put("risks", p.get("risks"));
        summary.put("simulated", p.get("simulated"));
        approvals.request(run.workspaceId(), run.id(), ApprovalKind.IMPLEMENT, proposal.hash(), store.json.writeValueAsString(summary), run.ownerUserId());
        store.appendEvent(run.id(), "APPROVAL_REQUESTED", Map.of("kind", "IMPLEMENT", "hash", proposal.hash()));
    }
}
