package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSteps;
import ai.devforge.agent.FixProposalDraft;
import ai.devforge.agent.FindingDraft;
import ai.devforge.approval.Approvals;
import ai.devforge.workflow.RunStatus;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Produces the fix proposal. A new proposal is a new artifact with a new hash, which also voids any approval given for an older one. */
@Component
class ProposalHandler implements StepHandler {
    private final AgentSteps agent;
    private final RunContextFactory contexts;
    private final RunDataStore data;
    private final RunStore store;
    private final Approvals approvals;

    ProposalHandler(AgentSteps agent, RunContextFactory contexts, RunDataStore data, RunStore store, Approvals approvals) {
        this.agent = agent;
        this.contexts = contexts;
        this.data = data;
        this.store = store;
        this.approvals = approvals;
    }

    private void recordSimulatedUsage(RunRow run) {
        data.addModelCall(run.id(), "PROPOSING_FIX", run.ownerUserId(), run.agentProvider(), run.agentModel(), 400, 250, 0, null, true);
        store.addUsage(run.id(), 400, 250, null);
    }

    @Override public RunStatus step() { return RunStatus.PROPOSING_FIX; }

    @Override
    public Outcome handle(RunRow run, StepContext ctx) {
        List<FindingDraft> findings = data.findingDrafts(run.id());
        FixProposalDraft proposal = agent.proposeFix(contexts.build(run, ctx), findings);
        approvals.supersedePending(run.id(), "a new fix proposal replaced the one under review");
        var art = data.addArtifact(run.id(), "FIX_PROPOSAL", proposal);
        store.appendEvent(run.id(), "ARTIFACT_CREATED", Map.of("type", "FIX_PROPOSAL", "hash", art.hash(), "files", proposal.filesToChange().size()));
        if (run.simulated()) recordSimulatedUsage(run);
        return Outcome.next(RunStatus.FIX_PROPOSAL_AVAILABLE, "proposal ready (" + proposal.filesToChange().size() + " file(s))");
    }
}
