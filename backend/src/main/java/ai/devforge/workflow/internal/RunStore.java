package ai.devforge.workflow.internal;

import ai.devforge.platform.DomainException;
import ai.devforge.workflow.RunMode;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.EventView;
import ai.devforge.workflow.RunViews.StepView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for runs, steps and events. Status only changes through {@link #move}, which is an optimistic compare-and-set on the current status. */
@Component
class RunStore {
    private static final String COLS = "id, workspace_id, type, mode, status, owner_user_id, title, notes, idempotency_key, request_hash, agent_provider, agent_instance_id, "
        + "agent_model, agent_fast_model, connection_id, simulated, await_reason, resume_state, failure_code, failure_step, failure_message, failure_retryable, cancel_requested, "
        + "incomplete, input_tokens, output_tokens, cost_usd, created_at, updated_at, version";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    final JdbcClient jdbc;
    final JsonMapper json;
    private final TransactionTemplate tx;

    RunStore(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
    }

    Optional<RunRow> find(UUID id) {
        return jdbc.sql("select " + COLS + " from run where id = ?").param(id).query(RunStore::map).optional();
    }

    RunRow get(UUID id) {
        return find(id).orElseThrow(() -> DomainException.notFound("RUN_NOT_FOUND", "Run not found"));
    }

    /** Compare-and-set: returns false if the run is no longer in {@code from} (someone else, e.g. a cancel, got there first). */
    boolean move(UUID id, RunStatus from, Move m) {
        return jdbc.sql("update run set status = ?, await_reason = ?, resume_state = ?, failure_code = ?, failure_step = ?, failure_message = ?, failure_retryable = ?, "
                + "version = version + 1, updated_at = now() where id = ? and status = ?")
            .params(m.to().name(), m.awaitReason(), m.resumeState(), m.failureCode(), m.failureStep(), m.failureMessage(), m.failureRetryable(), id, from.name()).update() == 1;
    }

    record TrackerUse(String provider, UUID connectionId) {}

    Optional<TrackerUse> trackerUse(UUID id) {
        return jdbc.sql("select tracker_provider, tracker_connection_id from run where id = ? and tracker_provider is not null").param(id)
            .query((rs, i) -> new TrackerUse(rs.getString(1), rs.getObject(2, UUID.class))).optional();
    }

    void setTitle(UUID id, String title) { jdbc.sql("update run set title = ?, updated_at = now() where id = ?").params(title, id).update(); }

    void setMode(UUID id, RunMode mode) { jdbc.sql("update run set mode = ?, updated_at = now() where id = ?").params(mode.name(), id).update(); }

    void requestCancel(UUID id) { jdbc.sql("update run set cancel_requested = true, updated_at = now() where id = ?").param(id).update(); }

    boolean cancelRequested(UUID id) { return jdbc.sql("select cancel_requested from run where id = ?").param(id).query(Boolean.class).optional().orElse(false); }

    void markIncomplete(UUID id) { jdbc.sql("update run set incomplete = true where id = ?").param(id).update(); }

    void addUsage(UUID id, long in, long out, BigDecimal cost) {
        jdbc.sql("update run set input_tokens = input_tokens + ?, output_tokens = output_tokens + ?, cost_usd = case when ?::numeric is null then cost_usd else coalesce(cost_usd, 0) + ?::numeric end where id = ?")
            .params(in, out, cost, cost, id).update();
    }

    // ---- steps
    int countSteps(UUID runId, String name) {
        return jdbc.sql("select count(*) from run_step where run_id = ? and name = ?").params(runId, name).query(Integer.class).single();
    }

    UUID startStep(UUID runId, String name, int attempt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into run_step (id, run_id, name, attempt, status) values (?,?,?,?, 'RUNNING')").params(id, runId, name, attempt).update();
        appendEvent(runId, "STEP_STARTED", Map.of("step", name, "attempt", attempt));
        return id;
    }

    void finishStep(UUID runId, UUID stepId, String name, String status, String detail) {
        jdbc.sql("update run_step set status = ?, finished_at = now(), detail = ? where id = ?").params(status, detail, stepId).update();
        appendEvent(runId, "STEP_FINISHED", detail == null ? Map.of("step", name, "status", status) : Map.of("step", name, "status", status, "detail", detail));
    }

    /** A step left RUNNING by a process that died is closed as interrupted before the step runs again. */
    void interruptStaleSteps(UUID runId, String name) {
        jdbc.sql("update run_step set status = 'FAILED', finished_at = now(), detail = 'interrupted (worker lost)' where run_id = ? and name = ? and status = 'RUNNING'").params(runId, name).update();
    }

    int countSucceededSteps(UUID runId, String name) {
        return jdbc.sql("select count(*) from run_step where run_id = ? and name = ? and status = 'SUCCEEDED'").params(runId, name).query(Integer.class).single();
    }

    List<StepView> steps(UUID runId) {
        return jdbc.sql("select id, name, attempt, status, started_at, finished_at, detail from run_step where run_id = ? order by started_at")
            .param(runId).query((rs, i) -> new StepView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7))).list();
    }

    // ---- events (per-run monotonically increasing seq)
    int appendEvent(UUID runId, String type, Map<String, ?> payload) {
        return tx.execute(s -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtext(?))").param(runId.toString()).query().singleRow();
            return jdbc.sql("insert into run_event (run_id, seq, type, payload) select ?, coalesce(max(seq), 0) + 1, ?, ? from run_event where run_id = ? returning seq")
                .params(runId, type, json.writeValueAsString(payload), runId).query(Integer.class).single();
        });
    }

    List<EventView> eventsAfter(UUID runId, int afterSeq, int limit) {
        return jdbc.sql("select seq, type, payload, created_at from run_event where run_id = ? and seq > ? order by seq limit ?").params(runId, afterSeq, limit)
            .query((rs, i) -> new EventView(rs.getInt(1), rs.getString(2), json.readValue(rs.getString(3), MAP), rs.getTimestamp(4).toInstant())).list();
    }

    private static RunRow map(ResultSet rs, int i) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at"), updated = rs.getTimestamp("updated_at");
        return new RunRow(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("type"), RunMode.valueOf(rs.getString("mode")),
            RunStatus.valueOf(rs.getString("status")), rs.getObject("owner_user_id", UUID.class), rs.getString("title"), rs.getString("notes"), rs.getString("idempotency_key"),
            rs.getString("request_hash"), rs.getString("agent_provider"), rs.getObject("agent_instance_id", UUID.class), rs.getString("agent_model"), rs.getString("agent_fast_model"),
            rs.getObject("connection_id", UUID.class), rs.getBoolean("simulated"), rs.getString("await_reason"), rs.getString("resume_state"), rs.getString("failure_code"),
            rs.getString("failure_step"), rs.getString("failure_message"), (Boolean) rs.getObject("failure_retryable"), rs.getBoolean("cancel_requested"), rs.getBoolean("incomplete"),
            rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("cost_usd"), created.toInstant(), updated.toInstant(), rs.getLong("version"));
    }
}
