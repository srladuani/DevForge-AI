package ai.devforge.workspace.internal;

import ai.devforge.audit.AuditLog;
import ai.devforge.identity.CurrentUser;
import ai.devforge.identity.Users;
import ai.devforge.platform.DomainException;
import ai.devforge.repository.Repositories;
import ai.devforge.repository.RepositoryView;
import ai.devforge.workspace.ProjectTypeDefinition;
import ai.devforge.workspace.ProjectTypes;
import ai.devforge.workspace.RepoRole;
import ai.devforge.workspace.PublishSettings;
import ai.devforge.workspace.TrackerBinding;
import ai.devforge.workspace.WorkspaceAccess;
import ai.devforge.workspace.WorkspaceKind;
import ai.devforge.workspace.WorkspacePolicy;
import ai.devforge.workspace.WorkspaceRepoView;
import ai.devforge.workspace.WorkspaceRole;
import ai.devforge.workspace.WorkspaceRole.Permission;
import ai.devforge.workspace.WorkspaceView;
import ai.devforge.workspace.Workspaces;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
class WorkspaceService implements Workspaces, WorkspaceAccess {
    private static final int MAX_HINTS_BYTES = 4096;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProjectTypes projectTypes;
    private final Repositories repositories;
    private final Users users;
    private final AuditLog audit;

    WorkspaceService(JdbcClient jdbc, JsonMapper json, ProjectTypes projectTypes, Repositories repositories, Users users, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.projectTypes = projectTypes;
        this.repositories = repositories;
        this.users = users;
        this.audit = audit;
    }

    // ---- access
    @Override
    public Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId) {
        return jdbc.sql("select role from workspace_member where workspace_id = ? and user_id = ?").params(workspaceId, userId)
            .query((rs, i) -> WorkspaceRole.valueOf(rs.getString(1))).optional();
    }

    @Override
    public WorkspaceRole require(UUID workspaceId, UUID userId, Permission permission) {
        WorkspaceRole role = roleOf(workspaceId, userId).orElseThrow(() -> DomainException.notFound("WORKSPACE_NOT_FOUND", "Project not found"));
        if (!role.allows(permission)) throw DomainException.forbidden("INSUFFICIENT_ROLE", "Your role in this project does not allow this action");
        return role;
    }

    // ---- workspaces
    @Transactional
    WorkspaceView create(CurrentUser creator, String name, WorkspaceKind kind, WorkspacePolicy policy) {
        ProjectTypeDefinition type = projectTypes.get(kind);
        if (!type.enabled()) throw DomainException.unprocessable("PROJECT_TYPE_NOT_AVAILABLE", type.displayName() + " is not available yet");
        UUID id = UUID.randomUUID();
        WorkspacePolicy p = validated(policy == null ? WorkspacePolicy.defaults() : policy);
        jdbc.sql("insert into workspace (id, name, kind, policy, created_by) values (?, ?, ?, ?, ?)").params(id, name.trim(), kind.name(), json.writeValueAsString(p), creator.id()).update();
        jdbc.sql("insert into workspace_member (workspace_id, user_id, role) values (?, ?, 'ADMIN')").params(id, creator.id()).update();
        audit.record(creator.id().toString(), "WORKSPACE_CREATED", "workspace", id.toString(), kind + " " + name);
        return get(id, creator.id());
    }

    @Override
    public WorkspaceView get(UUID id, UUID asUserId) {
        WorkspaceRole role = require(id, asUserId, Permission.VIEW);
        return row(id, role);
    }

    List<WorkspaceView> listFor(UUID userId) {
        return jdbc.sql("select w.id, w.name, w.kind, w.policy, w.created_at, m.role from workspace w join workspace_member m on m.workspace_id = w.id "
                + "where m.user_id = ? order by w.created_at desc").param(userId)
            .query((rs, i) -> view(rs, WorkspaceRole.valueOf(rs.getString("role")))).list();
    }

    @Transactional
    WorkspaceView update(UUID id, UUID userId, String name, WorkspacePolicy policy) {
        WorkspaceRole role = require(id, userId, Permission.MANAGE);
        if (name != null) jdbc.sql("update workspace set name = ?, version = version + 1 where id = ?").params(name.trim(), id).update();
        if (policy != null) jdbc.sql("update workspace set policy = ?, version = version + 1 where id = ?").params(json.writeValueAsString(validated(policy)), id).update();
        audit.record(userId.toString(), "WORKSPACE_UPDATED", "workspace", id.toString(), (name != null ? "name " : "") + (policy != null ? "policy" : ""));
        return row(id, role);
    }

    @Override public WorkspacePolicy policy(UUID id) { return policyOf(id); }

    @Override
    public java.util.Optional<TrackerBinding> tracker(UUID id) {
        return jdbc.sql("select provider_type, site_hint from workspace_tracker where workspace_id = ?").param(id)
            .query((rs, i) -> new TrackerBinding(rs.getString(1), rs.getString(2))).optional();
    }

    @Override
    public PublishSettings publishSettings(UUID id) {
        return jdbc.sql("select target_branch, branch_prefix, draft from workspace_publish where workspace_id = ?").param(id)
            .query((rs, i) -> new PublishSettings(rs.getString(1), rs.getString(2), rs.getBoolean(3))).optional().orElse(PublishSettings.defaults());
    }

    PublishSettings publishSettingsFor(UUID id, UUID userId) {
        require(id, userId, Permission.VIEW);
        return publishSettings(id);
    }

    @Transactional
    PublishSettings setPublishSettings(UUID id, UUID userId, PublishSettings s) {
        require(id, userId, Permission.MANAGE);
        PublishSettings clean = new PublishSettings(s.targetBranch() == null || s.targetBranch().isBlank() ? null : s.targetBranch().strip(), s.branchPrefix() == null ? PublishSettings.DEFAULT_PREFIX : s.branchPrefix().strip(), s.draft());
        String problem = clean.problem();
        if (problem != null) throw DomainException.badRequest("INVALID_PUBLISH_SETTINGS", problem);
        jdbc.sql("""
                insert into workspace_publish (workspace_id, target_branch, branch_prefix, draft, updated_by) values (?,?,?,?,?)
                on conflict (workspace_id) do update set target_branch = excluded.target_branch, branch_prefix = excluded.branch_prefix, draft = excluded.draft, updated_by = excluded.updated_by, updated_at = now()""")
            .params(id, clean.targetBranch(), clean.branchPrefix(), clean.draft(), userId).update();
        audit.record(userId.toString(), "PUBLISH_SETTINGS_SET", "workspace", id.toString(), (clean.targetBranch() == null ? "default branch" : clean.targetBranch()) + ", prefix " + clean.branchPrefix() + ", draft " + clean.draft());
        return clean;
    }

    java.util.Optional<TrackerBinding> trackerFor(UUID id, UUID userId) {
        require(id, userId, Permission.VIEW);
        return tracker(id);
    }

    @Transactional
    TrackerBinding setTracker(UUID id, UUID userId, String providerType, String siteHint) {
        require(id, userId, Permission.MANAGE);
        String hint = siteHint == null || siteHint.isBlank() ? null : siteHint.strip();
        jdbc.sql("""
                insert into workspace_tracker (workspace_id, provider_type, site_hint, updated_by) values (?,?,?,?)
                on conflict (workspace_id) do update set provider_type = excluded.provider_type, site_hint = excluded.site_hint, updated_by = excluded.updated_by, updated_at = now()""")
            .params(id, providerType, hint, userId).update();
        audit.record(userId.toString(), "TRACKER_SET", "workspace", id.toString(), providerType);
        return new TrackerBinding(providerType, hint);
    }

    @Transactional
    void clearTracker(UUID id, UUID userId) {
        require(id, userId, Permission.MANAGE);
        jdbc.sql("delete from workspace_tracker where workspace_id = ?").param(id).update();
        audit.record(userId.toString(), "TRACKER_CLEARED", "workspace", id.toString(), null);
    }

    @Override
    public WorkspaceKind kind(UUID id) {
        return jdbc.sql("select kind from workspace where id = ?").param(id).query((rs, i) -> WorkspaceKind.valueOf(rs.getString(1))).optional()
            .orElseThrow(() -> DomainException.notFound("WORKSPACE_NOT_FOUND", "Project not found"));
    }

    // ---- members
    record Member(UUID userId, String email, String displayName, WorkspaceRole role) {}

    List<Member> members(UUID id, UUID userId) {
        require(id, userId, Permission.VIEW);
        return jdbc.sql("select u.id, u.email, u.display_name, m.role from workspace_member m join app_user u on u.id = m.user_id where m.workspace_id = ? order by u.email")
            .param(id).query((rs, i) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), WorkspaceRole.valueOf(rs.getString(4)))).list();
    }

    @Transactional
    Member setMember(UUID id, UUID actor, String email, WorkspaceRole role) {
        require(id, actor, Permission.MANAGE);
        CurrentUser target = users.findByEmail(email).orElseThrow(() -> DomainException.notFound("USER_NOT_FOUND", "That person has not signed in to DevForge yet"));
        if (target.id().equals(actor) && role != WorkspaceRole.ADMIN) {
            throw DomainException.conflict("CANNOT_DEMOTE_SELF", "You cannot lower your own role");
        }
        jdbc.sql("insert into workspace_member (workspace_id, user_id, role) values (?,?,?) on conflict (workspace_id, user_id) do update set role = excluded.role")
            .params(id, target.id(), role.name()).update();
        audit.record(actor.toString(), "MEMBER_SET", "workspace", id.toString(), target.email() + " -> " + role);
        return new Member(target.id(), target.email(), target.displayName(), role);
    }

    @Transactional
    void removeMember(UUID id, UUID actor, UUID memberId) {
        require(id, actor, Permission.MANAGE);
        if (memberId.equals(actor)) throw DomainException.conflict("CANNOT_REMOVE_SELF", "You cannot remove yourself");
        jdbc.sql("delete from workspace_member where workspace_id = ? and user_id = ?").params(id, memberId).update();
        audit.record(actor.toString(), "MEMBER_REMOVED", "workspace", id.toString(), memberId.toString());
    }

    // ---- repositories
    @Override
    public List<WorkspaceRepoView> repositories(UUID workspaceId) {
        return jdbc.sql("select repository_id, role, hints from workspace_repo where workspace_id = ? order by added_at").param(workspaceId)
            .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), RepoRole.valueOf(rs.getString(2)), rs.getString(3)}).list().stream()
            .map(r -> {
                RepositoryView v = repositories.get((UUID) r[0]);
                return new WorkspaceRepoView(v.id(), v.name(), (RepoRole) r[1], hints((String) r[2]), v.localPath(), v.defaultBranch(), v.headSha(), v.profile());
            }).toList();
    }

    List<WorkspaceRepoView> repositoriesFor(UUID workspaceId, UUID userId) {
        require(workspaceId, userId, Permission.VIEW);
        return repositories(workspaceId);
    }

    @Transactional
    WorkspaceRepoView addRepository(UUID workspaceId, UUID userId, String path, String name, RepoRole role, Map<String, Object> hints) {
        require(workspaceId, userId, Permission.MANAGE);
        RepositoryView repo = repositories.registerLocal(path, name);
        boolean exists = jdbc.sql("select count(*) from workspace_repo where workspace_id = ? and repository_id = ?").params(workspaceId, repo.id()).query(Integer.class).single() > 0;
        if (exists) throw DomainException.conflict("REPOSITORY_ALREADY_IN_PROJECT", "That repository is already part of this project");
        jdbc.sql("insert into workspace_repo (workspace_id, repository_id, role, hints) values (?,?,?,?)").params(workspaceId, repo.id(), role.name(), hintsJson(hints)).update();
        audit.record(userId.toString(), "REPOSITORY_ADDED", "workspace", workspaceId.toString(), repo.name() + " as " + role);
        return repositories(workspaceId).stream().filter(r -> r.repositoryId().equals(repo.id())).findFirst().orElseThrow();
    }

    @Transactional
    WorkspaceRepoView updateRepository(UUID workspaceId, UUID userId, UUID repoId, RepoRole role, Map<String, Object> hints) {
        require(workspaceId, userId, Permission.MANAGE);
        requireMembership(workspaceId, repoId);
        if (role != null) jdbc.sql("update workspace_repo set role = ? where workspace_id = ? and repository_id = ?").params(role.name(), workspaceId, repoId).update();
        if (hints != null) jdbc.sql("update workspace_repo set hints = ? where workspace_id = ? and repository_id = ?").params(hintsJson(hints), workspaceId, repoId).update();
        audit.record(userId.toString(), "REPOSITORY_UPDATED", "workspace", workspaceId.toString(), repoId.toString());
        return repositories(workspaceId).stream().filter(r -> r.repositoryId().equals(repoId)).findFirst().orElseThrow();
    }

    @Transactional
    void removeRepository(UUID workspaceId, UUID userId, UUID repoId) {
        require(workspaceId, userId, Permission.MANAGE);
        requireMembership(workspaceId, repoId);
        jdbc.sql("delete from workspace_repo where workspace_id = ? and repository_id = ?").params(workspaceId, repoId).update();
        audit.record(userId.toString(), "REPOSITORY_REMOVED", "workspace", workspaceId.toString(), repoId.toString());
    }

    WorkspaceRepoView refreshRepository(UUID workspaceId, UUID userId, UUID repoId) {
        require(workspaceId, userId, Permission.MANAGE);
        requireMembership(workspaceId, repoId);
        repositories.refresh(repoId);
        return repositories(workspaceId).stream().filter(r -> r.repositoryId().equals(repoId)).findFirst().orElseThrow();
    }

    // ---- helpers
    private void requireMembership(UUID workspaceId, UUID repoId) {
        boolean in = jdbc.sql("select count(*) from workspace_repo where workspace_id = ? and repository_id = ?").params(workspaceId, repoId).query(Integer.class).single() > 0;
        if (!in) throw DomainException.notFound("REPOSITORY_NOT_IN_PROJECT", "Repository is not part of this project");
    }

    private String hintsJson(Map<String, Object> hints) {
        String s = json.writeValueAsString(hints == null ? Map.of() : hints);
        if (s.getBytes().length > MAX_HINTS_BYTES) throw DomainException.badRequest("HINTS_TOO_LARGE", "Hints must be under " + MAX_HINTS_BYTES + " bytes");
        return s;
    }

    private Map<String, Object> hints(String text) {
        return json.readValue(text, new TypeReference<Map<String, Object>>() {});
    }

    private static WorkspacePolicy validated(WorkspacePolicy p) {
        if (p.maxRunCostUsd() < 0 || p.maxRunCostUsd() > 1000) throw DomainException.badRequest("INVALID_POLICY", "maxRunCostUsd must be between 0 and 1000");
        if (p.maxConcurrentRuns() < 1 || p.maxConcurrentRuns() > 20) throw DomainException.badRequest("INVALID_POLICY", "maxConcurrentRuns must be between 1 and 20");
        List<String> providers = p.allowedProviders() == null ? List.of() : p.allowedProviders().stream().map(String::toUpperCase).distinct().toList();
        return new WorkspacePolicy(providers, p.requireDifferentApprover(), p.maxRunCostUsd(), p.maxConcurrentRuns());
    }

    private WorkspacePolicy policyOf(UUID id) {
        String text = jdbc.sql("select policy from workspace where id = ?").param(id).query(String.class).optional()
            .orElseThrow(() -> DomainException.notFound("WORKSPACE_NOT_FOUND", "Project not found"));
        return json.readValue(text, WorkspacePolicy.class);
    }

    private WorkspaceView row(UUID id, WorkspaceRole role) {
        return jdbc.sql("select id, name, kind, policy, created_at from workspace where id = ?").param(id).query((rs, i) -> view(rs, role)).single();
    }

    private WorkspaceView view(ResultSet rs, WorkspaceRole role) throws SQLException {
        return new WorkspaceView(rs.getObject("id", UUID.class), rs.getString("name"), WorkspaceKind.valueOf(rs.getString("kind")),
            json.readValue(rs.getString("policy"), WorkspacePolicy.class), rs.getTimestamp("created_at").toInstant(), role);
    }
}
