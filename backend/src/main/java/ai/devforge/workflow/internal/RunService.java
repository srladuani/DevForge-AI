package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSelection;
import ai.devforge.agent.AgentSelectionRequest;
import ai.devforge.agent.ModelCatalog;
import ai.devforge.audit.AuditLog;
import ai.devforge.identity.CurrentUser;
import ai.devforge.platform.DomainException;
import ai.devforge.platform.Hashes;
import ai.devforge.workflow.RunMode;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.AgentView;
import ai.devforge.workflow.RunViews.ArtifactView;
import ai.devforge.workflow.RunViews.ChangeView;
import ai.devforge.workflow.RunViews.EventView;
import ai.devforge.workflow.RunViews.FailureView;
import ai.devforge.workflow.RunViews.FindingView;
import ai.devforge.workflow.RunViews.RunView;
import ai.devforge.workflow.RunViews.StepView;
import ai.devforge.workflow.RunViews.ValidationView;
import ai.devforge.workspace.ProjectTypeDefinition;
import ai.devforge.workspace.ProjectTypes;
import ai.devforge.workspace.WorkspaceAccess;
import ai.devforge.workspace.WorkspacePolicy;
import ai.devforge.workspace.WorkspaceRepoView;
import ai.devforge.workspace.WorkspaceRole;
import ai.devforge.workspace.WorkspaceRole.Permission;
import ai.devforge.workspace.Workspaces;
import ai.devforge.integration.TrackerException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RunService {
    static final Pattern TICKET_KEY = Pattern.compile("[A-Z][A-Z0-9]{1,9}-\\d{1,9}");

    private final RunStore store;
    private final RunDataStore data;
    private final JobQueue queue;
    private final WorkspaceAccess access;
    private final Workspaces workspaces;
    private final ProjectTypes projectTypes;
    private final ai.devforge.repository.ManagedRepositories managedRepositories;
    private final ModelCatalog catalog;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final TicketFetcher tickets;
    private final WorkflowProperties props;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    RunService(RunStore store, RunDataStore data, JobQueue queue, WorkspaceAccess access, Workspaces workspaces, ProjectTypes projectTypes, ai.devforge.repository.ManagedRepositories managedRepositories,
               ModelCatalog catalog, AuditLog audit, TransactionTemplate tx, TicketFetcher tickets, WorkflowProperties props, io.micrometer.core.instrument.MeterRegistry meters) {
        this.props = props;
        this.meters = meters;
        this.store = store;
        this.data = data;
        this.queue = queue;
        this.access = access;
        this.workspaces = workspaces;
        this.projectTypes = projectTypes;
        this.managedRepositories = managedRepositories;
        this.catalog = catalog;
        this.audit = audit;
        this.tx = tx;
        this.tickets = tickets;
    }

    record InputRequest(String kind, String ref, @org.jspecify.annotations.Nullable String text) {}
    record CreateCommand(RunMode mode, InputRequest input, List<UUID> repositoryIds, AgentSelectionRequest agent, String notes) {}
    record Created(RunView run, boolean replay) {}

    Created create(CurrentUser user, UUID workspaceId, CreateCommand cmd, String idempotencyKey) {
        access.require(workspaceId, user.id(), Permission.RUN);
        ProjectTypeDefinition type = projectTypes.get(workspaces.kind(workspaceId));
        if (!type.enabled()) throw DomainException.unprocessable("PROJECT_TYPE_NOT_AVAILABLE", type.displayName() + " is not available yet");

        String requestHash = Hashes.sha256(store.json.writeValueAsString(canonical(cmd)));
        if (idempotencyKey != null) {
            var existing = findByKey(workspaceId, idempotencyKey);
            if (existing != null) return replay(existing, requestHash, user);
        }
        validateInput(cmd.input());
        boolean fetchTicket = "TICKET".equals(cmd.input().kind()) && (cmd.input().text() == null || cmd.input().text().isBlank());
        TicketFetcher.Plan trackerPlan = null;
        if (fetchTicket) {
            try { trackerPlan = tickets.plan(workspaceId, user.id()); }
            catch (TrackerException e) { throw DomainException.unprocessable("TRACKER_" + e.kind(), e.getMessage()); }
        }
        final TicketFetcher.Plan plan = trackerPlan;
        List<UUID> repoIds = cmd.repositoryIds() == null ? List.of() : cmd.repositoryIds().stream().distinct().toList();
        if (repoIds.isEmpty() || repoIds.size() > 10) throw DomainException.unprocessable("INVALID_REPOSITORIES", "Choose between 1 and 10 repositories from this project");
        List<WorkspaceRepoView> projectRepos = workspaces.repositories(workspaceId);
        for (UUID id : repoIds) {
            if (projectRepos.stream().noneMatch(r -> r.repositoryId().equals(id))) throw DomainException.unprocessable("REPOSITORY_NOT_IN_PROJECT", "Repository " + id + " is not part of this project");
        }
        // Things the caller can fix themselves are reported before capacity limits.
        AgentSelection agent = catalog.select(user.id(), workspaceId, cmd.agent());
        WorkspacePolicy policy = workspaces.policy(workspaceId);
        long active = store.jdbc.sql("select count(*) from run where workspace_id = ? and status in ('CREATED','INPUT_RETRIEVAL','CONTEXT_GATHERING','INVESTIGATION','PROPOSING_FIX','IMPLEMENTATION','VALIDATION')")
            .param(workspaceId).query(Long.class).single();
        checkQuota(user, agent.simulated());
        if (active >= policy.maxConcurrentRuns()) {
            throw new DomainException("TOO_MANY_ACTIVE_RUNS", HttpStatus.TOO_MANY_REQUESTS, "This project already has " + active + " run(s) executing; wait for one to reach a stopping point");
        }
        // Pin each repository to the commit it has right now; every later read and every citation is against that commit.
        // A repository DevForge cloned is first brought up to date with the branch the change will go into, using the starter's own connection.
        Map<UUID, String> pinned = new LinkedHashMap<>();
        Map<UUID, String> syncNotes = new LinkedHashMap<>();
        String projectTarget = workspaces.publishSettings(workspaceId).targetBranch();
        for (UUID id : repoIds) {
            WorkspaceRepoView r = projectRepos.stream().filter(x -> x.repositoryId().equals(id)).findFirst().orElseThrow();
            var synced = managedRepositories.sync(id, user.id(), r.targetBranch() != null ? r.targetBranch() : projectTarget);
            pinned.put(id, synced.repository().headSha());
            if (synced.note() != null) syncNotes.put(id, synced.note());
        }

        UUID runId = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                String title = cmd.input().kind().equals("TICKET") ? cmd.input().ref() : clip(cmd.input().text().strip().lines().findFirst().orElse(""), 80);
                store.jdbc.sql("""
                        insert into run (id, workspace_id, type, mode, status, owner_user_id, title, notes, idempotency_key, request_hash, agent_provider, agent_instance_id,
                                         agent_model, agent_fast_model, connection_id, simulated)
                        values (?,?,?,?,'CREATED',?,?,?,?,?,?,?,?,?,?,?)""")
                    .params(runId, workspaceId, type.workflowId(), cmd.mode().name(), user.id(), title, cmd.notes(), idempotencyKey, requestHash, agent.provider(),
                        agent.providerInstanceId(), agent.model(), agent.fastModel(), agent.connectionId(), agent.simulated()).update();
                data.addInput(runId, cmd.input().kind(), cmd.input().ref(), cmd.input().text() == null ? "" : cmd.input().text());
                if (plan != null) store.jdbc.sql("update run set tracker_provider = ?, tracker_connection_id = ? where id = ?").params(plan.binding().providerType(), plan.connection().id(), runId).update();
                for (UUID id : repoIds) {
                    WorkspaceRepoView r = projectRepos.stream().filter(x -> x.repositoryId().equals(id)).findFirst().orElseThrow();
                    data.addRepo(runId, id, r.name(), r.role().name(), pinned.get(id));
                }
                store.appendEvent(runId, "RUN_CREATED", Map.of("mode", cmd.mode().name(), "repositories", repoIds.size(), "provider", agent.provider(), "model", agent.model(), "simulated", agent.simulated()));
                syncNotes.forEach((repoId, note) -> store.appendEvent(runId, "REPOSITORY_NOT_REFRESHED", Map.of("repositoryId", repoId.toString(), "note", note)));
                queue.enqueue(runId, RunStatus.INPUT_RETRIEVAL.name());
                audit.record(user.id().toString(), "RUN_CREATED", "run", runId.toString(), cmd.mode() + " via " + agent.provider() + "/" + agent.model());
                meters.counter("devforge.runs.started", "mode", cmd.mode().name(), "simulated", String.valueOf(agent.simulated())).increment();
            });
        } catch (DuplicateKeyException e) {
            var existing = idempotencyKey == null ? null : findByKey(workspaceId, idempotencyKey);
            if (existing != null) return replay(existing, requestHash, user);
            throw e;
        }
        return new Created(view(store.get(runId), user), false);
    }

    private Created replay(RunRow existing, String requestHash, CurrentUser user) {
        if (!existing.requestHash().equals(requestHash)) {
            throw DomainException.unprocessable("IDEMPOTENCY_KEY_REUSED", "That Idempotency-Key was already used with a different request");
        }
        return new Created(view(existing, user), true);
    }

    private RunRow findByKey(UUID workspaceId, String key) {
        return store.jdbc.sql("select id from run where workspace_id = ? and idempotency_key = ?").params(workspaceId, key).query(UUID.class).optional().map(store::get).orElse(null);
    }

    private static Map<String, Object> canonical(CreateCommand c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", c.mode());
        m.put("input", c.input());
        m.put("repositories", c.repositoryIds() == null ? List.of() : c.repositoryIds().stream().map(UUID::toString).sorted().toList());
        m.put("agent", c.agent());
        m.put("notes", c.notes());
        return m;
    }

    /** Rolling-day limits per person, so one account (or a runaway script) cannot spend without bound. Simulated runs spend nothing and only count as runs. */
    private void checkQuota(CurrentUser user, boolean simulated) {
        if (props.maxRunsPerUserPerDay() > 0) {
            long runs = store.jdbc.sql("select count(*) from run where owner_user_id = ? and created_at > now() - interval '1 day'").param(user.id()).query(Long.class).single();
            if (runs >= props.maxRunsPerUserPerDay()) {
                throw new DomainException("QUOTA_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS, "You have started " + runs + " runs in the last 24 hours, which is the daily limit. Try again later.");
            }
        }
        if (props.maxTokensPerUserPerDay() > 0 && !simulated) {
            long tokens = store.jdbc.sql("select coalesce(sum(input_tokens + output_tokens), 0) from model_call where user_id = ? and not simulated and created_at > now() - interval '1 day'")
                .param(user.id()).query(Long.class).single();
            if (tokens >= props.maxTokensPerUserPerDay()) {
                throw new DomainException("QUOTA_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS, "Your runs used " + tokens + " model tokens in the last 24 hours, which is the daily limit. Try again later.");
            }
        }
    }

    private static void validateInput(InputRequest in) {
        if (in == null || in.kind() == null) throw DomainException.badRequest("INPUT_REQUIRED", "An input (ticket or prompt) is required");
        boolean fetchTicket = "TICKET".equals(in.kind()) && (in.text() == null || in.text().isBlank());
        if (!fetchTicket && (in.text() == null || in.text().isBlank())) throw DomainException.badRequest("TEXT_REQUIRED", "Describe the problem or paste the ticket text");
        if (in.text() != null && in.text().length() > 20_000) throw DomainException.badRequest("TEXT_TOO_LARGE", "Input text is limited to 20,000 characters");
        switch (in.kind()) {
            case "PROMPT" -> { }
            case "TICKET" -> {
                if (in.ref() == null || !TICKET_KEY.matcher(in.ref()).matches()) throw DomainException.badRequest("INVALID_TICKET_KEY", "Ticket key must look like ABC-123");
            }
            default -> throw DomainException.badRequest("UNSUPPORTED_INPUT_KIND", "Input kind must be PROMPT or TICKET");
        }
    }

    // ---- queries
    RunRow load(UUID runId, CurrentUser user, Permission p) {
        RunRow run = store.get(runId);
        access.require(run.workspaceId(), user.id(), p);
        return run;
    }

    RunView get(UUID runId, CurrentUser user) { return view(load(runId, user, Permission.VIEW), user); }

    List<RunView> list(UUID workspaceId, CurrentUser user, RunStatus status) {
        access.require(workspaceId, user.id(), Permission.VIEW);
        return store.jdbc.sql("select id from run where workspace_id = ? and (cast(? as text) is null or status = ?) order by created_at desc limit 100")
            .params(workspaceId, status == null ? null : status.name(), status == null ? null : status.name()).query(UUID.class).list().stream().map(id -> view(store.get(id), user)).toList();
    }

    List<EventView> events(UUID runId, CurrentUser user, int after) { load(runId, user, Permission.VIEW); return store.eventsAfter(runId, after, 500); }
    List<StepView> steps(UUID runId, CurrentUser user) { load(runId, user, Permission.VIEW); return store.steps(runId); }
    List<FindingView> findings(UUID runId, CurrentUser user) { load(runId, user, Permission.VIEW); return data.findings(runId); }
    List<ArtifactView> artifacts(UUID runId, CurrentUser user) { load(runId, user, Permission.VIEW); return data.artifacts(runId); }
    List<ChangeView> changes(UUID runId, CurrentUser user) { load(runId, user, Permission.VIEW); return data.changes(runId); }
    List<ValidationView> validations(UUID runId, CurrentUser user) { load(runId, user, Permission.VIEW); return data.validations(runId); }

    RunView view(RunRow r, CurrentUser user) {
        WorkspaceRole role = access.roleOf(r.workspaceId(), user.id()).orElse(null);
        return new RunView(r.id(), r.workspaceId(), r.type(), r.mode(), r.status(), r.ownerUserId(), r.title(), r.notes(),
            new AgentView(r.agentProvider(), r.agentModel(), r.agentFastModel(), r.simulated()), r.awaitReason(), r.resumeState(),
            r.failureCode() == null ? null : new FailureView(r.failureCode(), r.failureStep(), r.failureMessage(), Boolean.TRUE.equals(r.failureRetryable())),
            r.cancelRequested(), r.incomplete(), r.inputTokens(), r.outputTokens(), r.costUsd(), r.createdAt(), r.updatedAt(), data.repos(r.id()), data.inputs(r.id()),
            nextActions(r, user, role));
    }

    /** What this user can do next, so the UI never has to re-implement the rules. Enforcement still happens server-side on every call. */
    static List<String> nextActions(RunRow r, CurrentUser user, WorkspaceRole role) {
        List<String> a = new ArrayList<>();
        if (role == null || !role.allows(Permission.RUN)) return a;
        boolean owner = r.ownerUserId().equals(user.id());
        // the owner may ask to publish a real change once it awaits review or has been accepted (an approval by someone else is still required)
        if (owner && !r.simulated() && (r.status() == RunStatus.AWAITING_REVIEW || r.status() == RunStatus.COMPLETED)) a.add("publish");
        if (RunStateMachine.isFinal(r.status())) return a;
        switch (r.status()) {
            case AWAITING_INPUT -> {
                if ("CLARIFICATION".equals(r.awaitReason())) a.add("answer");
                else if (owner) a.add("resume");
            }
            case FINDINGS_AVAILABLE -> {
                if (owner) a.add("request-fix");
                a.add("complete");
            }
            case FIX_PROPOSAL_AVAILABLE -> {
                a.add("request-implementation");
                a.add("complete");
            }
            case AWAITING_REVIEW -> {
                a.add("accept");
                if (owner) a.add("request-changes");
            }
            case FAILED -> { if (owner && Boolean.TRUE.equals(r.failureRetryable())) a.add("resume"); }
            default -> { }
        }
        if (owner || role == WorkspaceRole.ADMIN) a.add("cancel");
        return a;
    }

    static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    static Comparator<RunView> newestFirst() { return Comparator.comparing(RunView::createdAt).reversed(); }
}
