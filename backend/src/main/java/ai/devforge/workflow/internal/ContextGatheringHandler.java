package ai.devforge.workflow.internal;

import ai.devforge.repository.RepoReader;
import ai.devforge.repository.Repositories;
import ai.devforge.repository.RepositoryView;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.RepoView;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Confirms every selected repository is readable at its pinned commit and records what was found. Deterministic; no model. */
@Component
class ContextGatheringHandler implements StepHandler {
    private final RunDataStore data;
    private final RunStore store;
    private final Repositories repositories;
    private final RepoReader reader;

    ContextGatheringHandler(RunDataStore data, RunStore store, Repositories repositories, RepoReader reader) {
        this.data = data;
        this.store = store;
        this.repositories = repositories;
        this.reader = reader;
    }

    @Override public RunStatus step() { return RunStatus.CONTEXT_GATHERING; }

    @Override
    public Outcome handle(RunRow run, StepContext ctx) {
        List<RepoView> repos = data.repos(run.id());
        for (RepoView r : repos) {
            RepositoryView v = repositories.get(r.repositoryId());
            int files = reader.listFiles(r.repositoryId(), r.baseSha(), null, null).size();
            var tools = v.profile() == null ? List.of() : v.profile().buildTools().stream().map(t -> t.tool() + (t.toolchain().isEmpty() ? "" : " (" + t.toolchain() + ")")).toList();
            store.appendEvent(run.id(), "REPO_CONTEXT", Map.of("repository", r.name(), "role", r.role(), "sha", r.baseSha().substring(0, 12), "files", files, "buildTools", tools));
        }
        return Outcome.next(RunStatus.INVESTIGATION, repos.size() + " repositor" + (repos.size() == 1 ? "y" : "ies") + " ready");
    }
}
