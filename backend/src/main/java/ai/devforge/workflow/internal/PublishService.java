package ai.devforge.workflow.internal;

import ai.devforge.approval.ApprovalDecided;
import ai.devforge.approval.ApprovalKind;
import ai.devforge.approval.Approvals;
import ai.devforge.audit.AuditLog;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.Connections;
import ai.devforge.identity.CurrentUser;
import ai.devforge.integration.GitHostException;
import ai.devforge.integration.GitHosts;
import ai.devforge.integration.HostCredential;
import ai.devforge.integration.RemoteRepo;
import ai.devforge.platform.DomainException;
import ai.devforge.platform.Hashes;
import ai.devforge.repository.Repositories;
import ai.devforge.workflow.RunViews.ChangeView;
import ai.devforge.workflow.RunViews.PublicationView;
import ai.devforge.workflow.RunViews.PublishRequested;
import ai.devforge.workspace.WorkspaceRole.Permission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishing a finished change to a Git host. The run's owner asks; the plan (every patch, branch, title, PR text) becomes an artifact; a person
 * with approval rights approves exactly that plan; only then does anything leave the platform, using the owner's own credential.
 */
@Service
class PublishService {
    private final RunService runs;
    private final ExportService exports;
    private final RunDataStore data;
    private final RunStore store;
    private final PublicationStore publications;
    private final Publisher publisher;
    private final Approvals approvals;
    private final Connections connections;
    private final GitHosts hosts;
    private final Repositories repositories;
    private final AuditLog audit;
    private final ai.devforge.workspace.Workspaces workspaces;

    PublishService(RunService runs, ExportService exports, RunDataStore data, RunStore store, PublicationStore publications, Publisher publisher, Approvals approvals, Connections connections,
                   GitHosts hosts, Repositories repositories, AuditLog audit, ai.devforge.workspace.Workspaces workspaces) {
        this.workspaces = workspaces;
        this.runs = runs;
        this.exports = exports;
        this.data = data;
        this.store = store;
        this.publications = publications;
        this.publisher = publisher;
        this.approvals = approvals;
        this.connections = connections;
        this.hosts = hosts;
        this.repositories = repositories;
        this.audit = audit;
    }

    PublishRequested request(UUID runId, CurrentUser user) {
        RunRow run = ownerRun(runId, user);
        ExportService.Prepared p = exports.prepare(runId, user);
        for (ChangeView c : p.changes()) {
            if (PatchScanner.scan(c.patch()).stream().anyMatch(s -> s.severity().equals("BLOCK"))) throw DomainException.unprocessable("SECRET_IN_PATCH", "The patch for " + c.repoName() + " appears to contain a credential, so it cannot be published");
        }
        var settings = workspaces.publishSettings(run.workspaceId());
        var projectRepos = workspaces.repositories(run.workspaceId());
        Map<String, HostCredential> creds = new java.util.HashMap<>();
        Instant commitTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<PublishPlan.PlanRepo> repos = new ArrayList<>();
        try {
            for (ChangeView c : p.changes()) {
                String remoteUrl = repositories.remoteUrl(c.repositoryId()).orElseThrow(() -> DomainException.unprocessable("NO_REMOTE", c.repoName() + " has no 'origin' remote, so there is nowhere to publish it"));
                RemoteRepo remote = hosts.detect(remoteUrl);
                HostCredential cred = creds.computeIfAbsent(remote.hostType() + ":" + remote.owner() + "/" + remote.name(), k -> credentialFor(user, remote));
                String repoTarget = projectRepos.stream().filter(wr -> wr.repositoryId().equals(c.repositoryId())).map(ai.devforge.workspace.WorkspaceRepoView::targetBranch).filter(java.util.Objects::nonNull).findFirst().orElse(null);
                String base = repoTarget != null ? repoTarget : settings.targetBranch() != null ? settings.targetBranch() : hosts.defaultBranch(remote.hostType(), cred, remote);
                String baseSource = repoTarget != null ? "REPOSITORY" : settings.targetBranch() != null ? "PROJECT" : "HOST_DEFAULT";
                repos.add(new PublishPlan.PlanRepo(c.id(), c.repositoryId(), c.repoName(), remote.hostType(), remote.owner(), remote.name(), remote.pushUrl(), base, Hashes.sha256(c.patch()), c.files(),
                    p.subject() + "\n\n" + ExportService.messageBody(p, c, runId), baseSource));
            }
        } catch (GitHostException e) {
            throw switch (e.kind()) {
                case UNAVAILABLE -> new DomainException("HOST_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "The Git host could not be reached right now (" + e.getMessage() + "); try again");
                default -> DomainException.unprocessable("HOST_" + e.kind(), e.getMessage());
            };
        }
        PublishPlan plan = new PublishPlan(repos.getFirst().hostType(), p.branch(), p.subject(), exports.prBody(runId, p), settings.draft(), commitTime.toString(), repos);
        var artifact = data.addArtifact(runId, "PUBLISH_PLAN", plan);
        var approval = approvals.request(run.workspaceId(), runId, ApprovalKind.PR, artifact.hash(), summary(plan), user.id());
        store.appendEvent(runId, "PUBLISH_REQUESTED", Map.of("branch", plan.branch(), "repositories", repos.size(), "hash", artifact.hash()));
        audit.record(user.id().toString(), "PUBLISH_REQUESTED", "run", runId.toString(), plan.branch());
        return new PublishRequested(approval.id(), plan.branch(), artifact.hash());
    }

    /** Runs inside the approval decision's transaction; throwing rolls the decision back so an approval never outlives its plan. */
    void onDecided(ApprovalDecided e) {
        var artifact = data.currentArtifact(e.runId(), "PUBLISH_PLAN").orElseThrow(() -> DomainException.conflict("NO_PLAN", "There is no publication plan"));
        if (!artifact.hash().equals(e.artifactHash())) throw DomainException.conflict("ARTIFACT_CHANGED", "The publication plan changed after this approval was requested");
        store.appendEvent(e.runId(), "APPROVAL_DECIDED", Map.of("kind", e.kind().name(), "approved", e.approved(), "by", e.decidedBy().toString()));
        if (!e.approved()) return;
        RunRow run = store.get(e.runId());
        PublishPlan plan = store.json.readValue(artifact.content(), PublishPlan.class);
        for (PublishPlan.PlanRepo r : plan.repos()) {
            UUID connectionId = connections.findUsableForRepository(run.ownerUserId(), ConnectionKind.GIT_HOST, r.hostType(), r.owner(), r.name())
                .orElseThrow(() -> DomainException.unprocessable("NO_USABLE_CONNECTION", "The run's owner no longer has a verified " + r.hostType() + " connection that covers " + r.owner() + "/" + r.name())).id();
            if (!publications.create(run.id(), plan, r, connectionId, artifact.hash())) throw DomainException.conflict("ALREADY_PUBLISHED", r.repoName() + " has already been published or is being published");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publisher.runInBackground(e.runId()); }
        });
    }

    /** Tries again what failed, with the owner's current credential. Safe to repeat: nothing is created twice. */
    List<PublicationView> retry(UUID runId, CurrentUser user) {
        ownerRun(runId, user);
        var failed = publications.unfinished(runId);
        if (failed.isEmpty()) throw DomainException.conflict("NOTHING_TO_RETRY", "There is no unfinished publication for this run");
        failed.forEach(f -> publications.reset(f.id(), findForRemote(user, f.hostType(), f.remote())
            .orElseThrow(() -> DomainException.unprocessable("NO_USABLE_CONNECTION", "Add and verify a " + f.hostType() + " connection that covers " + f.remote() + " under My connections, then retry")).id()));
        publisher.runNow(runId);
        return publications.forRun(runId);
    }

    List<PublicationView> list(UUID runId, CurrentUser user) {
        runs.load(runId, user, Permission.VIEW);
        return publications.forRun(runId);
    }

    private RunRow ownerRun(UUID runId, CurrentUser user) {
        RunRow run = runs.load(runId, user, Permission.RUN);
        if (!run.ownerUserId().equals(user.id())) throw DomainException.forbidden("OWNER_ONLY", "Only the run's owner can publish it: it uses the owner's own GitHub credential");
        return run;
    }

    private java.util.Optional<ai.devforge.identity.ConnectionView> findForRemote(CurrentUser user, String hostType, String ownerSlashName) {
        int slash = ownerSlashName.indexOf('/');
        return connections.findUsableForRepository(user.id(), ConnectionKind.GIT_HOST, hostType, ownerSlashName.substring(0, slash), ownerSlashName.substring(slash + 1));
    }

    private HostCredential credentialFor(CurrentUser user, RemoteRepo remote) {
        String repo = remote.owner() + "/" + remote.name();
        var conn = connections.findUsableForRepository(user.id(), ConnectionKind.GIT_HOST, remote.hostType(), remote.owner(), remote.name())
            .orElseThrow(() -> DomainException.unprocessable("NO_USABLE_CONNECTION", "Add and verify a " + remote.hostType() + " connection that covers " + repo
                + " under My connections (a connection with no repository limit, limited to " + repo + ", or to " + remote.owner() + "/*), then try again"));
        return new HostCredential(conn.id(), user.id(), () -> connections.reveal(conn.id(), user.id()), s -> connections.replaceSecret(conn.id(), user.id(), s));
    }

    private String summary(PublishPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", plan.draft() ? "Push a new branch and open a draft pull request" : "Push a new branch and open a pull request");
        m.put("host", plan.hostType());
        m.put("branch", plan.branch());
        m.put("title", plan.prTitle());
        m.put("draft", plan.draft());
        m.put("repositories", plan.repos().stream().map(r -> Map.of("repository", r.repoName(), "host", r.hostType(), "remote", r.owner() + "/" + r.name(), "base", r.baseBranch(), "baseSource", String.valueOf(r.baseSource()), "files", r.files())).toList());
        return store.json.writeValueAsString(m);
    }
}
