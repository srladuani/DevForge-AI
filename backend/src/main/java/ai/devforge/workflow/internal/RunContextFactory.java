package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSelection;
import ai.devforge.agent.RepoTarget;
import ai.devforge.agent.RunContext;
import ai.devforge.agent.UsageRecorder;
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
    private final RunStore store;
    private final Repositories repositories;

    RunContextFactory(RunDataStore data, RunStore store, Repositories repositories) {
        this.data = data;
        this.store = store;
        this.repositories = repositories;
    }

    @SuppressWarnings("unchecked")
    RunContext build(RunRow run, StepHandler.StepContext step) {
        List<InputView> inputs = data.inputs(run.id()).stream().filter(i -> !i.kind().equals("REVIEW_FEEDBACK")).toList();
        String text = inputs.stream().map(InputView::text).collect(Collectors.joining("\n\n"));
        List<String> keywords = inputs.stream().filter(i -> i.normalized() != null).findFirst()
            .map(i -> (List<String>) i.normalized().getOrDefault("keywords", List.of())).orElse(List.of());
        List<RepoTarget> repos = data.repos(run.id()).stream().map(r -> target(r.repositoryId(), r.name(), r.role(), r.baseSha())).toList();
        AgentSelection agent = new AgentSelection(run.agentProvider(), run.agentInstanceId(), run.agentModel(), run.agentModel(), run.agentFastModel(), run.connectionId(), run.simulated());
        UsageRecorder usage = (stepName, in, out, cached) -> {
            data.addModelCall(run.id(), stepName, run.ownerUserId(), run.agentProvider(), run.agentModel(), in, out, cached, null, run.simulated());
            store.addUsage(run.id(), in, out, null);
        };
        return new RunContext(run.id(), run.ownerUserId(), agent, text, keywords, repos, step.attempt(), step.cancelRequested(), usage);
    }

    /** The build tool that has a test command wins; otherwise the first detected one. */
    private RepoTarget target(java.util.UUID repoId, String name, String role, String sha) {
        RepoProfile p = repositories.get(repoId).profile();
        RepoProfile.BuildTool tool = p == null ? null : p.buildTools().stream().filter(t -> t.testCommand() != null).findFirst().orElse(p.buildTools().stream().findFirst().orElse(null));
        return new RepoTarget(repoId, name, RepoRole.valueOf(role), sha, tool == null ? null : tool.testCommand(), tool == null ? null : tool.tool(), tool == null ? "" : tool.root());
    }
}
