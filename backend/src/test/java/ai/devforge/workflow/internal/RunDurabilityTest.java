package ai.devforge.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ai.devforge.identity.Users;
import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.RunFixtures;
import ai.devforge.workflow.WorkflowTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunDurabilityTest extends WorkflowTestBase {
    @Autowired JobQueue queue;
    @Autowired JobWorker worker;
    @Autowired Users users;

    @Test
    void aStepLeftBehindByACrashedWorkerIsPickedUpAgainWithoutDuplicatingItsOutput() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        int findings = count("select count(*) from finding where run_id = ?::uuid", run);
        int evidence = count("select count(*) from evidence where run_id = ?::uuid", run);
        assertThat(findings).isPositive();

        // The state a hard crash leaves: run mid-INVESTIGATION, its step RUNNING, its job leased by a worker that no longer exists.
        jdbc.sql("update run set status = 'INVESTIGATION' where id = ?::uuid").param(run).update();
        jdbc.sql("insert into run_step (id, run_id, name, attempt, status) values (?, ?::uuid, 'INVESTIGATION', 9, 'RUNNING')").params(UUID.randomUUID(), run).update();
        UUID job = UUID.randomUUID();
        jdbc.sql("insert into job (id, run_id, step, state, attempts, lease_until, locked_by) values (?, ?::uuid, 'INVESTIGATION', 'LEASED', 1, now() - interval '1 minute', 'dead-worker')").params(job, run).update();

        awaitStatus(run, "FINDINGS_AVAILABLE");
        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(jdbc.sql("select state from job where id = ?").param(job).query(String.class).single()));
        assertThat(jdbc.sql("select attempts from job where id = ?").param(job).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select locked_by from job where id = ?").param(job).query(String.class).single()).isNotEqualTo("dead-worker");
        assertThat(count("select count(*) from finding where run_id = ?::uuid", run)).as("findings were replaced, not duplicated").isEqualTo(findings);
        assertThat(count("select count(*) from evidence where run_id = ?::uuid", run)).isEqualTo(evidence);
        assertThat(jdbc.sql("select status || ': ' || detail from run_step where run_id = ?::uuid and attempt = 9").param(run).query(String.class).single()).isEqualTo("FAILED: interrupted (worker lost)");
    }

    @Test
    void everyQueuedJobIsClaimedByExactlyOneOfManyCompetingWorkers() throws Exception {
        worker.setPaused(true);
        try {
            Thread.sleep(300); // let a poll that was already running finish
            UUID owner = users.findByEmail(u("owner")).orElseThrow().id();
            List<UUID> jobs = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                var r = RunFixtures.runAwaitingApproval(jdbc, UUID.fromString(ws), owner);
                UUID id = UUID.randomUUID();
                jdbc.sql("insert into job (id, run_id, step) values (?, ?, 'INVESTIGATION')").params(id, r.runId()).update();
                jobs.add(id);
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                String workerId = "contender-" + w;
                futures.add(pool.submit(() -> {
                    go.await();
                    List<UUID> mine = new ArrayList<>();
                    while (true) {
                        var claimed = queue.claim(workerId, Duration.ofSeconds(30));
                        if (claimed.isEmpty()) return mine;
                        mine.add(claimed.get().id());
                        queue.complete(claimed.get().id(), workerId);
                    }
                }));
            }
            go.countDown();
            List<UUID> all = new ArrayList<>();
            for (var f : futures) all.addAll(f.get());
            pool.shutdown();
            assertThat(all).as("no job lost").containsAll(jobs);
            assertThat(all.stream().filter(jobs::contains).toList()).as("no job claimed twice").doesNotHaveDuplicates();
        } finally {
            worker.setPaused(false);
        }
    }

    @Test
    void leasesExpireWhenNotRenewedAndHeartbeatsKeepThemAlive() throws Exception {
        worker.setPaused(true);
        try {
            Thread.sleep(300);
            UUID owner = users.findByEmail(u("owner")).orElseThrow().id();
            var r = RunFixtures.runAwaitingApproval(jdbc, UUID.fromString(ws), owner);
            UUID id = UUID.randomUUID();
            jdbc.sql("insert into job (id, run_id, step) values (?, ?, 'INVESTIGATION')").params(id, r.runId()).update();

            var job = queue.claim("w1", Duration.ofSeconds(1)).orElseThrow();
            assertThat(job.id()).isEqualTo(id);
            assertThat(queue.claim("w2", Duration.ofSeconds(1))).as("a leased job is not offered to others").isEmpty();
            assertThat(queue.heartbeat(id, "w2", Duration.ofSeconds(60))).as("only the lease holder can renew").isFalse();
            assertThat(queue.heartbeat(id, "w1", Duration.ofSeconds(60))).isTrue();
            Thread.sleep(1300);
            assertThat(queue.requeueExpired()).as("renewed lease survives its original expiry").isZero();

            jdbc.sql("update job set lease_until = now() - interval '1 second' where id = ?").param(id).update();
            assertThat(queue.requeueExpired()).isEqualTo(1);
            assertThat(queue.claim("w2", Duration.ofSeconds(30))).isPresent();
            queue.complete(id, "w2");
        } finally {
            worker.setPaused(false);
        }
    }

    @Test
    void aJobForAStateTheRunHasAlreadyLeftIsIgnored() throws Exception {
        UUID owner = users.findByEmail(u("owner")).orElseThrow().id();
        var r = RunFixtures.runAwaitingApproval(jdbc, UUID.fromString(ws), owner); // run is AWAITING_APPROVAL
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into job (id, run_id, step) values (?, ?, 'INVESTIGATION')").params(id, r.runId()).update();
        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(jdbc.sql("select state from job where id = ?").param(id).query(String.class).single()));
        assertThat(jdbc.sql("select status from run where id = ?").param(r.runId()).query(String.class).single()).isEqualTo("AWAITING_APPROVAL");
        assertThat(count("select count(*) from run_event where run_id = ?::uuid", r.runId().toString())).isZero();
        assertThat(count("select count(*) from run_step where run_id = ?::uuid", r.runId().toString())).isZero();
    }

    @Test
    void eventSequenceNumbersAreGaplessEvenWhenManyThreadsAppend() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        UUID runId = UUID.fromString(run);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        int before = count("select count(*) from run_event where run_id = ?::uuid", run);
        for (int i = 0; i < 40; i++) fs.add(pool.submit(() -> store().appendEvent(runId, "NOTICE", java.util.Map.of("n", 1))));
        for (var f : fs) f.get();
        pool.shutdown();
        List<Integer> seqs = jdbc.sql("select seq from run_event where run_id = ?::uuid order by seq").param(run).query(Integer.class).list();
        assertThat(seqs).hasSize(before + 40).isEqualTo(java.util.stream.IntStream.rangeClosed(1, before + 40).boxed().toList());
        assertThat(Collections.max(seqs)).isEqualTo(before + 40);
    }

    @Autowired RunStore storeBean;

    private RunStore store() { return storeBean; }

    private int count(String sql, String runId) { return jdbc.sql(sql).param(runId).query(Integer.class).single(); }
}
