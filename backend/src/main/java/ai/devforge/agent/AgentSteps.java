package ai.devforge.agent;

import java.util.List;

/**
 * What the workflow asks an agent to do at each LLM-backed step. M1 ships a simulation; M2 replaces it with the real tool loop
 * behind the same interface. Implementations return drafts only: the workflow persists, verifies evidence, and decides state.
 *
 * @throws LlmException for provider failures (the workflow decides retry / pause / fail)
 */
public interface AgentSteps {
    InvestigationResult investigate(RunContext ctx);

    FixProposalDraft proposeFix(RunContext ctx, List<FindingDraft> findings);

    List<ChangeDraft> implement(RunContext ctx, FixProposalDraft proposal, String reviewerFeedback);

    List<ValidationDraft> validate(RunContext ctx, List<ChangeDraft> changes);
}
