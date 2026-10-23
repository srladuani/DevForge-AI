package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSteps;
import ai.devforge.agent.ChangeDraft;
import ai.devforge.agent.FixProposalDraft;
import ai.devforge.approval.ApprovalKind;
import ai.devforge.approval.Approvals;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.RepoView;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Produces the change set. Refuses to run unless an approval exists for exactly the current proposal (defence in depth: the state
 * machine already gates entry). Nothing is applied to any checkout; the result is a patch per repository.
 */
@Component
class ImplementationHandler implements StepHandler {
    private final AgentSteps agent;
    private final RunContextFactory contexts;
    private final RunDataStore data;
    private final RunStore store;
    private final Approvals approvals;

    ImplementationHandler(AgentSteps agent, RunContextFactory contexts, RunDataStore data, RunStore store, Approvals approvals) {
        this.agent = agent;
        this.contexts = contexts;
        this.data = data;
        this.store = store;
        this.approvals = approvals;
    }

    /** Reviewer feedback, plus (after a failed validation) what failed, so the next attempt can fix the cause instead of guessing. */
    private String feedbackFor(RunRow run, int attempt) {
        String feedback = data.latestReviewFeedback(run.id());
        if (attempt <= 1) return feedback;
        String failure = data.validations(run.id()).stream().filter(v -> v.attempt() == attempt - 1 && v.status().equals("FAILED"))
            .map(v -> v.repoName() + ": " + v.summary()).collect(java.util.stream.Collectors.joining("\n\n"));
        if (failure.isBlank()) return feedback;
        return (feedback == null ? "" : feedback) + "\n\u0000validation-failure\n" + failure;
    }

    @Override public RunStatus step() { return RunStatus.IMPLEMENTATION; }

    @Override
    public Outcome handle(RunRow run, StepContext ctx) {
        var artifact = data.currentArtifact(run.id(), "FIX_PROPOSAL");
        if (artifact.isEmpty() || !approvals.hasValidApproval(run.id(), ApprovalKind.IMPLEMENT, artifact.get().hash())) {
            return Outcome.fail("APPROVAL_REQUIRED", RunStatus.IMPLEMENTATION, "No valid approval exists for the current fix proposal", false);
        }
        FixProposalDraft proposal = store.json.readValue(artifact.get().content(), FixProposalDraft.class);
        int attempt = store.countSucceededSteps(run.id(), "IMPLEMENTATION") + 1;
        data.deleteChangesOfAttempt(run.id(), attempt);
        List<ChangeDraft> changes = agent.implement(contexts.build(run, ctx), proposal, feedbackFor(run, attempt));
        Map<java.util.UUID, RepoView> scope = new java.util.HashMap<>();
        data.repos(run.id()).forEach(r -> scope.put(r.repositoryId(), r));
        for (ChangeDraft c : changes) {
            RepoView repo = scope.get(c.repositoryId());
            if (repo == null) return Outcome.fail("CHANGE_OUTSIDE_SCOPE", RunStatus.IMPLEMENTATION, "The agent proposed a change to a repository that is not part of this run", false);
            data.addChange(run.id(), c.repositoryId(), repo.name(), repo.baseSha(), c.patch(), c.files(), c.simulated(), attempt);
            store.appendEvent(run.id(), "CHANGE_PREPARED", Map.of("repository", repo.name(), "files", c.files(), "attempt", attempt, "simulated", c.simulated()));
        }
        if (run.simulated()) {
            data.addModelCall(run.id(), "IMPLEMENTATION", run.ownerUserId(), run.agentProvider(), run.agentModel(), 600, 400, null, true);
            store.addUsage(run.id(), 600, 400, null);
        }
        return Outcome.next(RunStatus.VALIDATION, changes.size() + " change set(s), attempt " + attempt);
    }
}
