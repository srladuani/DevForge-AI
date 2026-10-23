package ai.devforge.workflow.internal;

import ai.devforge.approval.ApprovalDecided;
import ai.devforge.approval.ApprovalKind;
import ai.devforge.audit.AuditLog;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.Connections;
import ai.devforge.identity.CurrentUser;
import ai.devforge.platform.DomainException;
import ai.devforge.workflow.RunMode;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.RunView;
import ai.devforge.workspace.WorkspaceAccess;
import ai.devforge.workspace.WorkspaceRole;
import ai.devforge.workspace.WorkspaceRole.Permission;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The things a person can do to a run. Each checks the run's status, the person's role, and (for model-spending actions) that they own the run. */
@Service
class RunActions {
    static final int MAX_REVIEW_ATTEMPTS = 5;

    private final RunStore store;
    private final RunDataStore data;
    private final RunEngine engine;
    private final RunService service;
    private final WorkspaceAccess access;
    private final Connections connections;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    RunActions(RunStore store, RunDataStore data, RunEngine engine, RunService service, WorkspaceAccess access, Connections connections, AuditLog audit, TransactionTemplate tx) {
        this.store = store;
        this.data = data;
        this.engine = engine;
        this.service = service;
        this.access = access;
        this.connections = connections;
        this.audit = audit;
        this.tx = tx;
    }

    RunView cancel(UUID runId, CurrentUser user) {
        RunRow run = service.load(runId, user, Permission.RUN);
        boolean admin = access.roleOf(run.workspaceId(), user.id()).orElse(null) == WorkspaceRole.ADMIN;
        if (!run.ownerUserId().equals(user.id()) && !admin) throw DomainException.forbidden("OWNER_OR_ADMIN_ONLY", "Only the run's owner or a project admin can cancel it");
        if (RunStateMachine.isFinal(run.status())) throw DomainException.conflict("RUN_ALREADY_FINISHED", "This run has already finished");
        audit.record(user.id().toString(), "RUN_CANCEL_REQUESTED", "run", runId.toString(), run.status().name());
        if (RunStateMachine.isWorkerState(run.status()) || run.status() == RunStatus.CREATED) {
            // A worker may be mid-step: ask it to stop at the next checkpoint instead of yanking state from under it.
            store.requestCancel(runId);
            store.appendEvent(runId, "CANCEL_REQUESTED", Map.of("by", user.email()));
        } else if (!engine.advance(runId, run.status(), Move.to(RunStatus.CANCELLED), "cancelled by " + user.email())) {
            throw DomainException.conflict("RUN_CHANGED", "The run changed while cancelling; try again");
        }
        return service.get(runId, user);
    }

    RunView resume(UUID runId, CurrentUser user) {
        RunRow run = ownerAction(runId, user);
        if (run.status() == RunStatus.FAILED) {
            if (!Boolean.TRUE.equals(run.failureRetryable())) throw DomainException.conflict("NOT_RETRYABLE", "This failure cannot be retried; start a new run");
            RunStatus step = RunStatus.valueOf(run.failureStep());
            move(run, Move.to(step), "resumed by " + user.email());
        } else if (run.status() == RunStatus.AWAITING_INPUT && "CREDENTIAL".equals(run.awaitReason())) {
            var tracker = "INPUT_RETRIEVAL".equals(run.resumeState()) ? store.trackerUse(runId) : java.util.Optional.<RunStore.TrackerUse>empty();
            if (tracker.isPresent()) {
                var usable = connections.findUsable(run.ownerUserId(), ConnectionKind.TRACKER, tracker.get().provider())
                    .orElseThrow(() -> DomainException.unprocessable("NO_USABLE_CONNECTION", "Add and verify your " + tracker.get().provider() + " connection under My connections, then resume"));
                store.jdbc.sql("update run set tracker_connection_id = ? where id = ?").params(usable.id(), runId).update();
            } else {
                var usable = connections.findUsable(run.ownerUserId(), ConnectionKind.AI_PROVIDER, run.agentProvider())
                    .orElseThrow(() -> DomainException.unprocessable("NO_USABLE_CONNECTION", "Add and verify your " + run.agentProvider() + " key under My connections, then resume"));
                store.jdbc.sql("update run set connection_id = ? where id = ?").params(usable.id(), runId).update();
            }
            move(run, Move.to(RunStatus.valueOf(run.resumeState())), "credential fixed by " + user.email());
        } else {
            throw badState(run, "resume");
        }
        audit.record(user.id().toString(), "RUN_RESUMED", "run", runId.toString(), null);
        return service.get(runId, user);
    }

    RunView answer(UUID runId, CurrentUser user, String text) {
        RunRow run = service.load(runId, user, Permission.RUN);
        if (run.status() != RunStatus.AWAITING_INPUT || !"CLARIFICATION".equals(run.awaitReason())) throw badState(run, "answer");
        if (text == null || text.isBlank()) throw DomainException.badRequest("TEXT_REQUIRED", "Provide your answer");
        if (text.length() > 20_000) throw DomainException.badRequest("TEXT_TOO_LARGE", "Answer is limited to 20,000 characters");
        tx.executeWithoutResult(s -> {
            data.addInput(runId, "CLARIFICATION", null, text);
            move(run, Move.to(RunStatus.INPUT_RETRIEVAL), "answered by " + user.email());
        });
        return service.get(runId, user);
    }

    RunView requestFix(UUID runId, CurrentUser user, RunMode mode) {
        RunRow run = ownerAction(runId, user);
        if (run.status() != RunStatus.FINDINGS_AVAILABLE) throw badState(run, "request-fix");
        if (!run.simulated()) throw DomainException.unprocessable("MODE_NOT_AVAILABLE_FOR_PROVIDER", "Fix proposals with a real model arrive in a later milestone");
        if (mode != RunMode.PROPOSE_FIX && mode != RunMode.FIX_AND_PATCH) throw DomainException.badRequest("INVALID_MODE", "mode must be PROPOSE_FIX or FIX_AND_PATCH");
        tx.executeWithoutResult(s -> {
            store.setMode(runId, mode);
            move(run, Move.to(RunStatus.PROPOSING_FIX), "fix requested by " + user.email());
        });
        return service.get(runId, user);
    }

    RunView complete(UUID runId, CurrentUser user) {
        RunRow run = service.load(runId, user, Permission.RUN);
        if (run.status() != RunStatus.FINDINGS_AVAILABLE && run.status() != RunStatus.FIX_PROPOSAL_AVAILABLE) throw badState(run, "complete");
        move(run, Move.to(RunStatus.COMPLETED), "completed by " + user.email());
        return service.get(runId, user);
    }

    RunView requestImplementation(UUID runId, CurrentUser user) {
        RunRow run = service.load(runId, user, Permission.RUN);
        if (run.status() != RunStatus.FIX_PROPOSAL_AVAILABLE) throw badState(run, "request-implementation");
        tx.executeWithoutResult(s -> {
            engine.requestImplementationApproval(run);
            move(run, Move.awaitApproval(RunStatus.FIX_PROPOSAL_AVAILABLE), "implementation requested by " + user.email());
        });
        return service.get(runId, user);
    }

    RunView accept(UUID runId, CurrentUser user) {
        RunRow run = service.load(runId, user, Permission.RUN);
        if (run.status() != RunStatus.AWAITING_REVIEW) throw badState(run, "accept");
        move(run, Move.to(RunStatus.COMPLETED), "changes accepted by " + user.email());
        audit.record(user.id().toString(), "CHANGES_ACCEPTED", "run", runId.toString(), null);
        return service.get(runId, user);
    }

    RunView requestChanges(UUID runId, CurrentUser user, String feedback) {
        RunRow run = ownerAction(runId, user);
        if (run.status() != RunStatus.AWAITING_REVIEW) throw badState(run, "request-changes");
        if (feedback == null || feedback.isBlank()) throw DomainException.badRequest("TEXT_REQUIRED", "Say what should change");
        if (data.latestChangeAttempt(runId) >= MAX_REVIEW_ATTEMPTS) throw DomainException.conflict("TOO_MANY_ATTEMPTS", "This run has reached " + MAX_REVIEW_ATTEMPTS + " implementation attempts");
        tx.executeWithoutResult(s -> {
            data.addInput(runId, "REVIEW_FEEDBACK", null, feedback);
            move(run, Move.to(RunStatus.IMPLEMENTATION), "changes requested by " + user.email());
        });
        return service.get(runId, user);
    }

    /**
     * Turns a human decision into a state change inside the same transaction. If the run can no longer honour it (it moved on, or the
     * proposal changed) this throws and the approval decision is rolled back, so an approval can never outlive what it approved.
     */
    @EventListener
    void onApprovalDecided(ApprovalDecided e) {
        if (e.kind() != ApprovalKind.IMPLEMENT) throw DomainException.conflict("UNSUPPORTED_APPROVAL", "Approvals of kind " + e.kind() + " are not handled yet");
        RunRow run = store.get(e.runId());
        if (run.status() != RunStatus.AWAITING_APPROVAL) throw DomainException.conflict("RUN_NOT_AWAITING_APPROVAL", "The run is " + run.status() + " and no longer waiting for this approval");
        var proposal = data.currentArtifact(run.id(), "FIX_PROPOSAL").orElseThrow(() -> DomainException.conflict("NO_PROPOSAL", "There is no fix proposal"));
        if (!proposal.hash().equals(e.artifactHash())) throw DomainException.conflict("ARTIFACT_CHANGED", "The fix proposal changed after this approval was requested");
        store.appendEvent(run.id(), "APPROVAL_DECIDED", Map.of("kind", e.kind().name(), "approved", e.approved(), "by", e.decidedBy().toString()));
        Move next = e.approved() ? Move.to(RunStatus.IMPLEMENTATION) : Move.to(RunStatus.FIX_PROPOSAL_AVAILABLE);
        if (!engine.advance(run.id(), RunStatus.AWAITING_APPROVAL, next, e.approved() ? "implementation approved" : "implementation rejected")) {
            throw DomainException.conflict("RUN_CHANGED", "The run changed while the decision was recorded");
        }
    }

    // ---- helpers
    private RunRow ownerAction(UUID runId, CurrentUser user) {
        RunRow run = service.load(runId, user, Permission.RUN);
        if (!run.ownerUserId().equals(user.id())) {
            throw DomainException.forbidden("OWNER_ONLY", "Only the run's owner can do this: it uses the owner's own AI credentials");
        }
        return run;
    }

    private void move(RunRow run, Move move, String reason) {
        if (!engine.advance(run.id(), run.status(), move, reason)) throw DomainException.conflict("RUN_CHANGED", "The run changed while you were acting on it; reload and try again");
    }

    private static DomainException badState(RunRow run, String action) {
        return DomainException.conflict("INVALID_STATE_FOR_ACTION", "'" + action + "' is not possible while the run is " + run.status());
    }
}
