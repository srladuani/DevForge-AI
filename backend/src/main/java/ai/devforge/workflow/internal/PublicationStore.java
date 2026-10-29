package ai.devforge.workflow.internal;

import ai.devforge.workflow.RunViews.PublicationView;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL for publications (the intent records of pushes and pull requests). */
@Component
class PublicationStore {
    private final JdbcClient jdbc;

    PublicationStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Row(UUID id, UUID runId, UUID changeId, UUID repositoryId, String repoName, String hostType, String remote, String branch, String baseBranch, String status, UUID connectionId, String planHash) {}

    private static final String COLS = "id, run_id, change_id, repository_id, repo_name, host_type, remote, branch, base_branch, status, connection_id, plan_hash";

    /** @return false if this change already has a publication that is on its way or finished (it is never published twice) */
    boolean create(UUID runId, PublishPlan plan, PublishPlan.PlanRepo r, UUID connectionId, String planHash) {
        var existing = status(runId, r.changeId());
        if (existing != null && !existing.equals("FAILED") && !existing.equals("PENDING")) return false;
        jdbc.sql("delete from publication where run_id = ? and change_id = ?").params(runId, r.changeId()).update();
        jdbc.sql("insert into publication (id, run_id, change_id, repository_id, repo_name, host_type, remote, branch, base_branch, status, connection_id, plan_hash) values (?,?,?,?,?,?,?,?,?,'PENDING',?,?)")
            .params(UUID.randomUUID(), runId, r.changeId(), r.repositoryId(), r.repoName(), r.hostType(), r.owner() + "/" + r.name(), plan.branch(), r.baseBranch(), connectionId, planHash).update();
        return true;
    }

    String status(UUID runId, UUID changeId) { return jdbc.sql("select status from publication where run_id = ? and change_id = ?").params(runId, changeId).query(String.class).optional().orElse(null); }

    List<Row> unfinished(UUID runId) {
        return jdbc.sql("select " + COLS + " from publication where run_id = ? and status <> 'PR_OPEN' order by repo_name").param(runId)
            .query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getObject(11, UUID.class), rs.getString(12))).list();
    }

    void reset(UUID id, UUID connectionId) {
        jdbc.sql("update publication set status = 'PENDING', failure_code = null, failure_message = null, connection_id = ?, updated_at = now() where id = ?").params(connectionId, id).update();
    }

    void pushed(UUID id, String commitSha) { jdbc.sql("update publication set status = 'PUSHED', commit_sha = ?, failure_code = null, failure_message = null, updated_at = now() where id = ?").params(commitSha, id).update(); }

    void prOpen(UUID id, int number, String url) { jdbc.sql("update publication set status = 'PR_OPEN', pr_number = ?, pr_url = ?, failure_code = null, failure_message = null, updated_at = now() where id = ?").params(number, url, id).update(); }

    void failed(UUID id, String code, String message) { jdbc.sql("update publication set status = 'FAILED', failure_code = ?, failure_message = ?, updated_at = now() where id = ?").params(code, message, id).update(); }

    /** Runs with a publication that is unfinished and has not moved for a while: the process that was carrying it out is gone. */
    List<UUID> stalledRuns(java.time.Duration idle) {
        return jdbc.sql("select distinct run_id from publication where status in ('PENDING', 'PUSHED') and updated_at < now() - make_interval(secs => ?)").param(idle.toSeconds()).query(UUID.class).list();
    }

    List<PublicationView> forRun(UUID runId) {
        return jdbc.sql("select change_id, repo_name, status, remote, branch, base_branch, commit_sha, pr_number, pr_url, failure_code, failure_message from publication where run_id = ? order by repo_name").param(runId)
            .query((rs, i) -> new PublicationView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                (Integer) rs.getObject(8), rs.getString(9), rs.getString(10), rs.getString(11))).list();
    }
}
