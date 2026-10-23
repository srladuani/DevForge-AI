package ai.devforge.repository.internal;

import ai.devforge.audit.AuditLog;
import ai.devforge.platform.DomainException;
import ai.devforge.repository.RepoProfile;
import ai.devforge.repository.Repositories;
import ai.devforge.repository.RepositoryView;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
class RepositoryStore implements Repositories {
    private final JdbcClient jdbc;
    private final GitCli git;
    private final PathPolicy policy;
    private final ProfileService profiles;
    private final JsonMapper json;
    private final AuditLog audit;

    RepositoryStore(JdbcClient jdbc, GitCli git, PathPolicy policy, ProfileService profiles, JsonMapper json, AuditLog audit) {
        this.jdbc = jdbc;
        this.git = git;
        this.policy = policy;
        this.profiles = profiles;
        this.json = json;
        this.audit = audit;
    }

    @Override
    public RepositoryView registerLocal(String path, String nameOrNull) {
        Path root = policy.validateRepoRoot(path);
        String sha = git.headSha(root);
        String name = nameOrNull != null && !nameOrNull.isBlank() ? nameOrNull.trim() : root.getFileName().toString();
        UUID id = jdbc.sql("select id from repository where local_path = ?").param(root.toString()).query(UUID.class).optional().orElse(null);
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.sql("insert into repository (id, name, local_path) values (?, ?, ?)").params(id, name, root.toString()).update();
            audit.record("system", "REPOSITORY_REGISTERED", "repository", id.toString(), root.getFileName().toString());
        }
        return refresh(id);
    }

    @Override
    public Optional<RepositoryView> find(UUID id) {
        return jdbc.sql("select id, name, local_path, default_branch, head_sha, profile from repository where id = ?").param(id)
            .query((rs, i) -> new RepositoryView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("local_path"), rs.getString("default_branch"),
                rs.getString("head_sha"), rs.getString("profile") == null ? null : json.readValue(rs.getString("profile"), RepoProfile.class)))
            .optional();
    }

    @Override
    public RepositoryView get(UUID id) {
        return find(id).orElseThrow(() -> DomainException.notFound("REPOSITORY_NOT_FOUND", "Repository not found"));
    }

    @Override
    public Optional<String> remoteUrl(UUID id) {
        String url = git.run(rootOf(id), "config", "--get", "remote.origin.url").text().strip();
        return url.isEmpty() ? Optional.empty() : Optional.of(url);
    }

    @Override
    public RepositoryView refresh(UUID id) {
        RepositoryView current = get(id);
        Path root = policy.validateRepoRoot(current.localPath());
        String sha = git.headSha(root);
        String branch = git.currentBranch(root);
        RepoProfile profile = profiles.compute(root, sha);
        jdbc.sql("update repository set default_branch = ?, head_sha = ?, profile = ?, profile_sha = ? where id = ?")
            .params(branch, sha, json.writeValueAsString(profile), sha, id).update();
        return get(id);
    }

    /** Filesystem root of a registered repository (internal use only). */
    Path rootOf(UUID id) {
        return policy.validateRepoRoot(get(id).localPath());
    }

    static Path path(String s) { return Paths.get(s); }
}
