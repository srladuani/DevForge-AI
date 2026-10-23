package ai.devforge.workflow.internal;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the job table and runs steps on a bounded set of virtual threads. A running job renews its lease; if this process dies the lease
 * lapses and any worker (including this one after a restart) picks the job up again.
 */
@Component
class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final RunEngine engine;
    private final Semaphore permits;
    private final Duration lease;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("job-heartbeat").unstarted(r));
    private volatile boolean stopping;
    private volatile boolean paused;

    JobWorker(JobQueue queue, RunEngine engine, WorkflowProperties props) {
        this.queue = queue;
        this.engine = engine;
        this.permits = new Semaphore(Math.max(1, props.concurrency()));
        this.lease = Duration.ofSeconds(Math.max(2, props.leaseSeconds()));
    }

    @Scheduled(fixedDelayString = "${devforge.workflow.poll-millis:250}")
    void tick() {
        if (stopping || paused) return;
        try {
            int requeued = queue.requeueExpired();
            if (requeued > 0) log.warn("Re-queued {} job(s) whose lease expired", requeued);
            while (permits.tryAcquire()) {
                var job = queue.claim(workerId, lease);
                if (job.isEmpty()) {
                    permits.release();
                    break;
                }
                Thread.ofVirtual().name("run-" + job.get().runId()).start(() -> {
                    try {
                        process(job.get());
                    } finally {
                        permits.release();
                    }
                });
            }
        } catch (RuntimeException e) {
            log.error("Worker poll failed", e);
        }
    }

    private void process(JobQueue.Job job) {
        ScheduledFuture<?> beat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (!queue.heartbeat(job.id(), workerId, lease)) log.warn("Lost the lease on job {}", job.id());
            } catch (RuntimeException e) {
                log.warn("Heartbeat failed for job {}", job.id(), e);
            }
        }, lease.toMillis() / 3, lease.toMillis() / 3, TimeUnit.MILLISECONDS);
        try {
            engine.execute(job.runId(), job.step());
        } catch (Throwable t) {
            engine.failUnexpected(job.runId(), job.step(), t);
        } finally {
            beat.cancel(false);
            queue.complete(job.id(), workerId);
        }
    }

    /** Stops picking up new jobs (running ones finish). Used to drain a node before maintenance, and by tests that need the queue to themselves. */
    void setPaused(boolean paused) { this.paused = paused; }

    @PreDestroy
    void stop() {
        stopping = true;
        heartbeats.shutdownNow();
    }
}
