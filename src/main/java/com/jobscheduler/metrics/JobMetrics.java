package com.jobscheduler.metrics;

import com.jobscheduler.domain.model.JobPriority;
import com.jobscheduler.domain.model.JobType;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.domain.model.JobStatus;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * All Prometheus metrics for the job scheduler.
 *
 * Metrics exposed at /actuator/prometheus:
 *
 * Counters (ever-increasing):
 *   jobs_submitted_total{type, priority}   — jobs created via API
 *   jobs_completed_total{type}             — successful executions
 *   jobs_failed_total{type, reason}        — failed executions
 *   jobs_dead_letter_total{type}           — jobs that exhausted retries
 *
 * Gauges (current snapshot):
 *   jobs_pending_count                     — PENDING jobs right now
 *   jobs_running_count                     — RUNNING jobs right now
 *
 * Timers (histogram + percentiles):
 *   job_execution_duration_seconds{type}   — p50, p95, p99 latency
 *
 * Usage in Grafana:
 *   Throughput:  rate(jobs_completed_total[1m])
 *   Error rate:  rate(jobs_failed_total[1m]) / rate(jobs_submitted_total[1m])
 *   Queue depth: jobs_pending_count
 *   p99 latency: histogram_quantile(0.99, rate(job_execution_duration_seconds_bucket[5m]))
 */
@Component
public class JobMetrics {

    private final MeterRegistry registry;
    private final JobRepository jobRepository;

    // ── Counters ──────────────────────────────────────────────────────────────

    public JobMetrics(MeterRegistry registry, JobRepository jobRepository) {
        this.registry      = registry;
        this.jobRepository = jobRepository;

        // Register gauges — these pull live values from the DB on each scrape
        Gauge.builder("jobs.pending.count", jobRepository,
                repo -> repo.findByStatusIn(
                    java.util.List.of(JobStatus.PENDING), org.springframework.data.domain.Pageable.unpaged()
                ).getTotalElements())
            .description("Number of jobs currently waiting to be executed")
            .register(registry);

        Gauge.builder("jobs.running.count", jobRepository,
                repo -> repo.findByStatusIn(
                    java.util.List.of(JobStatus.RUNNING), org.springframework.data.domain.Pageable.unpaged()
                ).getTotalElements())
            .description("Number of jobs currently being executed")
            .register(registry);
    }

    // ── Job submitted ─────────────────────────────────────────────────────────

    /**
     * Call when a job is created via POST /jobs.
     * Tagged by type and priority for breakdown in Grafana.
     */
    public void recordSubmit(JobType type, JobPriority priority) {
        Counter.builder("jobs.submitted")
            .description("Total number of jobs submitted")
            .tag("type", type.name())
            .tag("priority", priority.name())
            .register(registry)
            .increment();
    }

    // ── Job completed ─────────────────────────────────────────────────────────

    /**
     * Call when a job finishes successfully.
     * Also records the execution duration for latency histograms.
     */
    public void recordCompletion(JobType type, long durationMs) {
        Counter.builder("jobs.completed")
            .description("Total number of successfully completed jobs")
            .tag("type", type.name())
            .register(registry)
            .increment();

        recordDuration(type, durationMs);
    }

    // ── Job failed ────────────────────────────────────────────────────────────

    /**
     * Call when a job fails (will be retried or moved to DEAD_LETTER).
     * reason: "error", "timeout", "no_handler"
     */
    public void recordFailure(JobType type, String reason) {
        Counter.builder("jobs.failed")
            .description("Total number of failed job executions")
            .tag("type", type.name())
            .tag("reason", reason)
            .register(registry)
            .increment();
    }

    // ── Dead letter ───────────────────────────────────────────────────────────

    /**
     * Call when a job exhausts all retries and moves to DEAD_LETTER.
     */
    public void recordDeadLetter(JobType type) {
        Counter.builder("jobs.dead_letter")
            .description("Total number of jobs moved to dead letter queue")
            .tag("type", type.name())
            .register(registry)
            .increment();
    }

    // ── Execution duration ────────────────────────────────────────────────────

    /**
     * Record how long a job took to execute.
     * Stored as a histogram — Prometheus computes p50/p95/p99 from buckets.
     */
    public void recordDuration(JobType type, long durationMs) {
        Timer.builder("job.execution.duration")
            .description("Job execution duration in milliseconds")
            .tag("type", type.name())
            .publishPercentiles(0.5, 0.95, 0.99)  // p50, p95, p99
            .publishPercentileHistogram()
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ── Retry recorded ────────────────────────────────────────────────────────

    public void recordRetry(JobType type) {
        Counter.builder("jobs.retried")
            .description("Total number of job retry attempts")
            .tag("type", type.name())
            .register(registry)
            .increment();
    }
}
