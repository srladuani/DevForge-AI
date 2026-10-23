package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSelection;
import ai.devforge.agent.RepoTarget;
import ai.devforge.agent.RunContext;
import ai.devforge.repository.RepoProfile;
import ai.devforge.repository.Repositories;
import ai.devforge.workflow.RunViews.InputView;
import ai.devforge.workspace.RepoRole;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Assembles what an agent step is allowed to know about a run: the owner's chosen model, the input text, and the pinned repositories. */
@Component
class RunContextFactory {
    private final RunDataStore data;
    private final Repositories repositories;

    RunContextFactory(RunDataStore data, Repositories repositories) {
        this.data = data;
        this.repositories = repositories;
    }

    @SuppressWarnings("unchecked")
    RunContext build(RunRow run, StepHandler.StepContext step) {
        List<InputView> inputs = data.inputs(run.id()).stream().filter(i -> !i.kind().equals("REVIEW_FEEDBACK")).toList();
        String text = inputs.stream().map(InputView::text).collect(Collectors.joining("\n\n"));
        List<String> keywords = inputs.stream().filter(i -> i.normalized() != null).findFirst()
            .map(i -> (List<String>) i.normalized().getOrDefault("keywords", List.of())).orElse(List.of());
        List<RepoTarget> repos = data.repos(run.id()).stream().map(r -> new RepoTarget(r.repositoryId(), r.name(), RepoRole.valueOf(r.role()), r.baseSha(), testCommand(r.repositoryId()))).toList();
        AgentSelection agent = new AgentSelection(run.agentProvider(), run.agentInstanceId(), run.agentModel(), run.agentModel(), run.agentFastModel(), run.connectionId(), run.simulated());
        return new RunContext(run.id(), run.ownerUserId(), agent, text, keywords, repos, step.attempt(), step.cancelRequested());
    }

    private String testCommand(java.util.UUID repoId) {
        RepoProfile p = repositories.get(repoId).profile();
        return p == null ? null : p.buildTools().stream().map(RepoProfile.BuildTool::testCommand).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
}
