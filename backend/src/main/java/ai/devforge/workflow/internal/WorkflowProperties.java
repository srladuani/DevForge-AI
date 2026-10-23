package ai.devforge.workflow.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param concurrency        runs executing at once in this process
 * @param leaseSeconds       how long a worker may hold a job without a heartbeat before it is handed to another worker
 * @param pollMillis         how often idle workers look for jobs
 * @param retryBackoffMillis base delay between retries of a transient model failure (multiplied by the attempt number)
 * @param maxModelAttempts   tries for a transient model failure before the step fails
 * @param queueName          the job queue this process serves; workers never claim jobs of another queue
 */
@ConfigurationProperties("devforge.workflow")
record WorkflowProperties(@DefaultValue("2") int concurrency, @DefaultValue("30") int leaseSeconds, @DefaultValue("250") long pollMillis,
                          @DefaultValue("500") long retryBackoffMillis, @DefaultValue("3") int maxModelAttempts, @DefaultValue("default") String queueName) {}
