package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSteps;
import ai.devforge.agent.Confidence;
import ai.devforge.agent.EvidenceDraft;
import ai.devforge.agent.FindingDraft;
import ai.devforge.agent.FindingLabel;
import ai.devforge.agent.InvestigationResult;
import ai.devforge.repository.EvidenceRef;
import ai.devforge.repository.EvidenceVerifier;
import ai.devforge.repository.VerifiedEvidence;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.RepoView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs the agent's investigation and then does what the agent cannot be trusted to do: re-read every cited line from the pinned commit
 * and downgrade any claim whose evidence does not check out. Evidence from a repository outside the run is rejected outright.
 */
@Component
class InvestigationHandler implements StepHandler {
    private final AgentSteps agent;
    private final RunContextFactory contexts;
    private final RunDataStore data;
    private final RunStore store;
    private final EvidenceVerifier verifier;

    InvestigationHandler(AgentSteps agent, RunContextFactory contexts, RunDataStore data, RunStore store, EvidenceVerifier verifier) {
        this.agent = agent;
        this.contexts = contexts;
        this.data = data;
        this.store = store;
        this.verifier = verifier;
    }

    @Override public RunStatus step() { return RunStatus.INVESTIGATION; }

    @Override
    public Outcome handle(RunRow run, StepContext ctx) {
        InvestigationResult result = agent.investigate(contexts.build(run, ctx));
        Map<UUID, RepoView> scope = new LinkedHashMap<>();
        data.repos(run.id()).forEach(r -> scope.put(r.repositoryId(), r));

        data.clearInvestigationOutputs(run.id()); // idempotent: a re-run replaces, never duplicates
        result.toolTrace().forEach(t -> store.appendEvent(run.id(), "TOOL_CALL", Map.of("tool", t.tool(), "arguments", t.arguments(), "result", t.resultSummary())));
        int unverified = 0;
        for (FindingDraft f : result.findings()) {
            List<UUID> evidenceIds = new ArrayList<>();
            int verified = 0;
            for (EvidenceDraft e : f.evidence()) {
                RepoView repo = scope.get(e.repositoryId());
                if (repo == null) {
                    // The agent cited a repository this run was never given: refuse it, keep a record of the attempt.
                    UUID id = data.addEvidence(run.id(), e.repositoryId(), "(outside this run)", "-", e.path(), e.startLine(), e.endLine(), null, null, false, "Repository is not part of this run");
                    evidenceIds.add(id);
                    continue;
                }
                VerifiedEvidence v = verifier.verify(new EvidenceRef(e.repositoryId(), repo.baseSha(), e.path(), e.startLine(), e.endLine()));
                evidenceIds.add(data.addEvidence(run.id(), e.repositoryId(), repo.name(), repo.baseSha(), e.path(), e.startLine(), e.endLine(), v.contentHash(), v.excerpt(), v.verified(), v.problem()));
                if (v.verified()) verified++;
            }
            var judgement = ai.devforge.workflow.EvidenceJudge.judge(f.label(), f.evidence().size(), verified);
            FindingLabel finalLabel = judgement.label();
            String reason = judgement.downgradeReason();
            if (finalLabel != f.label()) unverified++;
            data.addFinding(run.id(), "INVESTIGATION", finalLabel, f.label(), reason, f.text(), f.confidence() == null ? Confidence.LOW : f.confidence(), evidenceIds);
            store.appendEvent(run.id(), "FINDING_ADDED", Map.of("label", finalLabel.name(), "text", clip(f.text()), "evidence", evidenceIds.size()));
        }
        Map<String, Object> rootCause = new LinkedHashMap<>();
        rootCause.put("summary", result.summary());
        rootCause.put("hypotheses", result.hypotheses());
        rootCause.put("unknowns", result.unknowns());
        rootCause.put("simulated", run.simulated());
        rootCause.put("incomplete", result.incomplete());
        var art = data.addArtifact(run.id(), "ROOT_CAUSE", rootCause);
        store.appendEvent(run.id(), "ARTIFACT_CREATED", Map.of("type", "ROOT_CAUSE", "hash", art.hash()));
        if (result.incomplete()) {
            store.markIncomplete(run.id());
            store.appendEvent(run.id(), "INVESTIGATION_INCOMPLETE", Map.of("reason", String.valueOf(result.incompleteReason())));
        }
        recordUsage(run, result.usage().inputTokens(), result.usage().outputTokens(), result.usage().cachedInputTokens());
        return Outcome.next(RunStatus.FINDINGS_AVAILABLE, result.findings().size() + " finding(s)" + (unverified > 0 ? ", " + unverified + " downgraded" : ""));
    }

    private void recordUsage(RunRow run, long in, long out, long cached) {
        data.addModelCall(run.id(), "INVESTIGATION", run.ownerUserId(), run.agentProvider(), run.agentModel(), in, out, cached, null, run.simulated());
        store.addUsage(run.id(), in, out, null);
        store.appendEvent(run.id(), "USAGE", Map.of("step", "INVESTIGATION", "inputTokens", in, "outputTokens", out, "costKnown", false));
    }

    private static String clip(String s) { return s.length() > 240 ? s.substring(0, 240) + "…" : s; }
}
