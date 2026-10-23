package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSteps;
import ai.devforge.agent.ChangeDraft;
import ai.devforge.agent.ValidationDraft;
import ai.devforge.workflow.RunStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Runs validation for the latest change set. A failure loops back to implementation a bounded number of times, then goes to a human. */
@Component
class ValidationHandler implements StepHandler {
    static final int MAX_FIX_ATTEMPTS = 3;

    private final AgentSteps agent;
    private final RunContextFactory contexts;
    private final RunDataStore data;
    private final RunStore store;

    ValidationHandler(AgentSteps agent, RunContextFactory contexts, RunDataStore data, RunStore store) {
        this.agent = agent;
        this.contexts = contexts;
        this.data = data;
        this.store = store;
    }

    @Override public RunStatus step() { return RunStatus.VALIDATION; }

    @Override
    public Outcome handle(RunRow run, StepContext ctx) {
        int attempt = data.latestChangeAttempt(run.id());
        var stored = data.changesOfAttempt(run.id(), attempt);
        List<ChangeDraft> drafts = new ArrayList<>();
        for (var c : stored) drafts.add(new ChangeDraft(c.repositoryId(), c.repoName(), "", List.of(), true));
        data.deleteValidationsOfAttempt(run.id(), attempt);
        List<ValidationDraft> results = agent.validate(contexts.build(run, ctx), drafts);
        boolean failed = false;
        for (ValidationDraft v : results) {
            var change = stored.stream().filter(s -> s.repositoryId().equals(v.repositoryId())).findFirst().orElseThrow();
            data.addValidation(run.id(), change.id(), v.repositoryName(), v.command(), v.status().name(), v.summary(), attempt);
            store.appendEvent(run.id(), "VALIDATION_RESULT", Map.of("repository", v.repositoryName(), "status", v.status().name(), "attempt", attempt));
            failed |= v.status() == ValidationDraft.Status.FAILED;
        }
        if (failed && attempt < MAX_FIX_ATTEMPTS) return Outcome.next(RunStatus.IMPLEMENTATION, "validation failed; trying again (attempt " + (attempt + 1) + " of " + MAX_FIX_ATTEMPTS + ")");
        return Outcome.next(RunStatus.AWAITING_REVIEW, failed ? "validation still failing after " + attempt + " attempt(s); needs a human" : "ready for review");
    }
}
