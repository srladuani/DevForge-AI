package ai.devforge.workflow.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Point-in-time numbers an operator watches: runs by status, jobs waiting, and how long the oldest has waited. Refreshed on a timer, so a scrape never queries the database. */
@Component
class RunGauges {
    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> byStatus = new ConcurrentHashMap<>();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong oldestQueuedSeconds = new AtomicLong();

    RunGauges(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
        Gauge.builder("devforge.jobs.queued", queued, AtomicLong::get).description("Jobs waiting for a worker").register(meters);
        Gauge.builder("devforge.jobs.oldest.queued.seconds", oldestQueuedSeconds, AtomicLong::get).description("Age of the longest-waiting job; growing means workers are stuck or too few").register(meters);
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT15S")
    void refresh() {
        try {
            byStatus.values().forEach(a -> a.set(0));
            jdbc.sql("select status, count(*) from run group by status").query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list().forEach(e -> gauge(e.getKey()).set(e.getValue()));
            queued.set(jdbc.sql("select count(*) from job where state = 'QUEUED'").query(Long.class).single());
            oldestQueuedSeconds.set(jdbc.sql("select coalesce(extract(epoch from now() - min(available_at))::bigint, 0) from job where state = 'QUEUED' and available_at <= now()").query(Long.class).single());
        } catch (RuntimeException e) {
            // a failed refresh keeps the previous values; the database being down shows up in health
        }
    }

    private AtomicLong gauge(String status) {
        return byStatus.computeIfAbsent(status, s -> {
            AtomicLong v = new AtomicLong();
            Gauge.builder("devforge.run.status", v, AtomicLong::get).tag("status", s).description("Runs by status").register(meters);
            return v;
        });
    }
}
