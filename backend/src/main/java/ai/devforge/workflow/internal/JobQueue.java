package ai.devforge.workflow.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Database-backed job queue (ADR-0005). A worker leases a job with FOR UPDATE SKIP LOCKED; a lease that is not renewed expires and the
 * job is handed to another worker, so work survives a crashed or restarted process.
 */
@Component
class JobQueue {
    record Job(UUID id, UUID runId, String step, int attempts) {}

    private final JdbcClient jdbc;
    private final String queueName;

    JobQueue(JdbcClient jdbc, WorkflowProperties props) {
        this.jdbc = jdbc;
        this.queueName = props.queueName();
    }

    void enqueue(UUID runId, String step) {
        jdbc.sql("insert into job (id, run_id, step, queue) values (?,?,?,?)").params(UUID.randomUUID(), runId, step, queueName).update();
    }

    Optional<Job> claim(String workerId, Duration lease) {
        return jdbc.sql("""
                update job set state = 'LEASED', lease_until = now() + make_interval(secs => ?), locked_by = ?, attempts = attempts + 1
                where id = (select id from job where queue = ? and state = 'QUEUED' and available_at <= now() order by created_at for update skip locked limit 1)
                returning id, run_id, step, attempts""")
            .params(lease.toMillis() / 1000.0, workerId, queueName).query((rs, i) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4))).optional();
    }

    /** Extends the lease; false means the lease was lost (another worker owns the job now). */
    boolean heartbeat(UUID jobId, String workerId, Duration lease) {
        return jdbc.sql("update job set lease_until = now() + make_interval(secs => ?) where id = ? and locked_by = ? and state = 'LEASED'")
            .params(lease.toMillis() / 1000.0, jobId, workerId).update() == 1;
    }

    void complete(UUID jobId, String workerId) {
        jdbc.sql("update job set state = 'DONE', lease_until = null where id = ? and locked_by = ?").params(jobId, workerId).update();
    }

    /** Returns jobs whose lease expired (their worker died or stalled) to the queue. */
    int requeueExpired() {
        return jdbc.sql("update job set state = 'QUEUED', lease_until = null, locked_by = null where queue = ? and state = 'LEASED' and lease_until < now()").param(queueName).update();
    }

    int queued() { return jdbc.sql("select count(*) from job where queue = ? and state = 'QUEUED'").param(queueName).query(Integer.class).single(); }
}
