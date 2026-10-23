package ai.devforge.workflow.internal;

import ai.devforge.execution.WorkCopies;
import ai.devforge.execution.WorkCopy;
import ai.devforge.identity.Connections;
import ai.devforge.identity.Users;
import ai.devforge.integration.GitHostException;
import ai.devforge.integration.GitHosts;
import ai.devforge.integration.HostCredential;
import ai.devforge.integration.PullRequestRef;
import ai.devforge.integration.RemoteRepo;
import ai.devforge.platform.DomainException;
import ai.devforge.platform.Hashes;
import ai.devforge.platform.Redactor;
import ai.devforge.repository.Repositories;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Carries out an approved publication with the run owner's own Git host credential: a commit made from a clean copy plus the stored patch,
 * pushed to a new branch (never forced), then a draft pull request. Every step is repeatable: the commit is deterministic, an existing branch
 * with the same commit is accepted, and an existing open pull request for the branch is reused. A failure is recorded and can be retried.
 */
@Component
class Publisher {
    private static final Logger log = LoggerFactory.getLogger(Publisher.class);
    private final PublicationStore publications;
    private final RunDataStore data;
    private final RunStore store;
    private final Repositories repositories;
    private final WorkCopies copies;
    private final GitHosts hosts;
    private final Connections connections;
    private final Users users;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    Publisher(PublicationStore publications, RunDataStore data, RunStore store, Repositories repositories, WorkCopies copies, GitHosts hosts, Connections connections, Users users) {
        this.publications = publications;
        this.data = data;
        this.store = store;
        this.repositories = repositories;
        this.copies = copies;
        this.hosts = hosts;
        this.connections = connections;
        this.users = users;
    }

    void runInBackground(UUID runId) {
        executor.submit(() -> {
            try {
                runNow(runId);
            } catch (RuntimeException e) {
                log.error("Publication of run {} crashed", runId, e);
            }
        });
    }

    void runNow(UUID runId) {
        RunRow run = store.get(runId);
        var artifact = data.currentArtifact(runId, "PUBLISH_PLAN");
        for (PublicationStore.Row p : publications.unfinished(runId)) {
            if (artifact.isEmpty() || !artifact.get().hash().equals(p.planHash())) {
                fail(run, p, "PLAN_CHANGED", "The approved plan is no longer the current one; request publication again");
                continue;
            }
            PublishPlan plan = store.json.readValue(artifact.get().content(), PublishPlan.class);
            PublishPlan.PlanRepo repo = plan.repos().stream().filter(r -> r.changeId().equals(p.changeId())).findFirst().orElse(null);
            if (repo == null) { fail(run, p, "PLAN_CHANGED", "This change is not part of the approved plan"); continue; }
            publish(run, plan, repo, p);
        }
    }

    private void publish(RunRow run, PublishPlan plan, PublishPlan.PlanRepo repo, PublicationStore.Row p) {
        store.appendEvent(run.id(), "PUBLISH_STARTED", Map.of("repository", repo.repoName(), "branch", plan.branch()));
        try {
            var change = data.changes(run.id()).stream().filter(c -> c.id().equals(repo.changeId())).findFirst().orElseThrow(() -> new IllegalStateException("change gone"));
            if (!Hashes.sha256(change.patch()).equals(repo.patchHash())) { fail(run, p, "PLAN_CHANGED", "The patch changed after it was approved"); return; }
            var owner = users.find(run.ownerUserId()).orElseThrow(() -> new IllegalStateException("owner gone"));
            HostCredential cred = new HostCredential(p.connectionId(), run.ownerUserId(), () -> {
                try {
                    return connections.reveal(p.connectionId(), run.ownerUserId());
                } catch (DomainException e) {
                    throw new GitHostException(GitHostException.Kind.AUTH_INVALID, "The connection used for this publication is no longer available");
                }
            });
            RemoteRepo remote = new RemoteRepo(plan.hostType(), repo.owner(), repo.name(), repo.pushUrl());
            String sha;
            try (WorkCopy copy = copies.openWithHistory(run.id() + "-pub-" + repo.repoName(), Path.of(repositories.get(repo.repositoryId()).localPath()), change.baseSha())) {
                copy.applyPatch(change.patch(), Set.copyOf(change.files()));
                sha = copy.commit(repo.commitMessage(), owner.displayName().replaceAll("[<>\\r\\n]", ""), owner.email(), Instant.parse(plan.commitTime()));
                copy.push(remote.pushUrl(), plan.branch(), hosts.gitAuthHeader(plan.hostType(), cred));
            }
            publications.pushed(p.id(), sha);
            store.appendEvent(run.id(), "BRANCH_PUSHED", Map.of("repository", repo.repoName(), "branch", plan.branch(), "commit", sha));
            PullRequestRef pr = hosts.findOpenPullRequest(plan.hostType(), cred, remote, plan.branch()).orElseGet(() -> openOrFind(plan, cred, remote, repo));
            publications.prOpen(p.id(), pr.number(), pr.url());
            store.appendEvent(run.id(), "PULL_REQUEST_OPENED", Map.of("repository", repo.repoName(), "number", pr.number(), "url", pr.url()));
        } catch (GitHostException e) {
            fail(run, p, switch (e.kind()) { case AUTH_INVALID -> "HOST_AUTH"; case NOT_FOUND -> "HOST_NOT_FOUND"; case REJECTED -> "HOST_REJECTED"; case UNAVAILABLE -> "HOST_UNAVAILABLE"; case UNSUPPORTED_REMOTE -> "HOST_REMOTE"; }, e.getMessage());
        } catch (WorkCopy.PushRejected e) {
            fail(run, p, switch (e.code()) { case "AUTH" -> "HOST_AUTH"; case "REJECTED" -> "BRANCH_EXISTS"; default -> "HOST_UNAVAILABLE"; }, e.getMessage());
        } catch (WorkCopy.EditRejected e) {
            fail(run, p, "PATCH_DOES_NOT_APPLY", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Publishing {} of run {} failed unexpectedly", repo.repoName(), run.id(), e);
            fail(run, p, "INTERNAL_ERROR", "Unexpected error while publishing");
        }
    }

    private PullRequestRef openOrFind(PublishPlan plan, HostCredential cred, RemoteRepo remote, PublishPlan.PlanRepo repo) {
        try {
            return hosts.openPullRequest(plan.hostType(), cred, remote, plan.branch(), repo.baseBranch(), plan.prTitle(), plan.prBody(), plan.draft());
        } catch (GitHostException e) {
            if (e.kind() == GitHostException.Kind.REJECTED && e.getMessage().toLowerCase().contains("already exists")) {
                return hosts.findOpenPullRequest(plan.hostType(), cred, remote, plan.branch()).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    private void fail(RunRow run, PublicationStore.Row p, String code, String message) {
        String safe = Redactor.redact(message);
        publications.failed(p.id(), code, safe);
        store.appendEvent(run.id(), "PUBLISH_FAILED", Map.of("repository", p.repoName(), "code", code, "message", safe));
    }
}
