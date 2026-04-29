package com.jobscheduler.scheduler;

import com.jobscheduler.config.AppProperties;
import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The scheduling brain of the system.
 *
 * Runs on a fixed poll interval (default 5s). On each tick:
 *
 * 1. CRON RESCHEDULER — finds completed recurring jobs and
 *    resets them to PENDING with the next computed run time.
 *    This is what makes cron jobs repeat automatically.
 *
 * 2. DELAYED JOB ACTIVATOR — finds any PENDING one-shot jobs
 *    whose next_run_at is now in the past (edge case: jobs submitted
 *    while no workers were running). Workers handle this naturally
 *    via the SKIP LOCKED query, so this is a safety net.
 *
 * 3. ANTI-STARVATION — promotes LOW priority jobs that have been
 *    waiting longer than 5 minutes.
 *
 * In Phase 3 (leader election), this loop will be guarded by
 * leaderElectionSvc.isLeader() so only one node runs it.
 * For now (monolith phase), it runs on every instance.
 */
@Component
public class SchedulerLoop {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);

    private final AppProperties appProperties;
    private final JobRepository jobRepository;
    private final CronExpressionEvaluator cronEvaluator;

    private ScheduledExecutorService scheduler;

    public SchedulerLoop(AppProperties appProperties,
                         JobRepository jobRepository,
                         CronExpressionEvaluator cronEvaluator) {
        this.appProperties = appProperties;
        this.jobRepository  = jobRepository;
        this.cronEvaluator  = cronEvaluator;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-loop");
            t.setDaemon(false);
            return t;
        });

        long intervalMs = appProperties.getPollIntervalMs();
        scheduler.scheduleWithFixedDelay(
            this::tick,
            intervalMs,   // initial delay — let app fully start first
            intervalMs,   // between ticks
            TimeUnit.MILLISECONDS
        );

        log.info("SchedulerLoop started — polling every {}ms", intervalMs);
    }

    @PreDestroy
    public void stop() {
        log.info("SchedulerLoop shutting down");
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Main tick ─────────────────────────────────────────────────────────────

    /**
     * Called every pollIntervalMs. Handles all scheduling concerns.
     * Errors are caught so one bad tick never stops the loop.
     */
    @Transactional
    void tick() {
        try {
            rescheduleCronJobs();
            promoteStaleJobs();
            logQueueStats();
        } catch (Exception e) {
            log.error("Error during scheduler tick — will retry next interval", e);
        }
    }

    // ── Cron rescheduler ──────────────────────────────────────────────────────

    /**
     * Find recurring (cron) jobs that have just COMPLETED and
     * schedule their next execution.
     *
     * This is the core of recurring job support:
     *   Job completes → SchedulerLoop finds it → computes nextRunAt
     *   → resets to PENDING → worker picks it up at the right time
     */

    void rescheduleCronJobs() {
        // Find completed cron jobs (have a cronExpression, status=COMPLETED)
        List<Job> completedCronJobs = jobRepository
            .findCompletedCronJobs(PageRequest.of(0, appProperties.getDispatchBatchSize()))
            .getContent();

        if (completedCronJobs.isEmpty()) return;

        log.debug("Rescheduling {} completed cron jobs", completedCronJobs.size());

        for (Job job : completedCronJobs) {
            cronEvaluator.nextRunAfter(job.getCronExpression(), ZonedDateTime.now())
                .ifPresentOrElse(
                    nextRun -> {
                        job.setStatus(JobStatus.PENDING);
                        job.setNextRunAt(nextRun);
                        job.setRetryCount(0);
                        job.setErrorMessage(null);
                        job.setWorkerId(null);
                        jobRepository.save(job);
                        log.info("Cron job '{}' ({}) rescheduled → next run at {}",
                            job.getName(), job.getId(), nextRun);
                    },
                    () -> log.warn("Cron job '{}' has no future execution — leaving COMPLETED",
                        job.getName())
                );
        }
    }

    // ── Anti-starvation ───────────────────────────────────────────────────────

    /**
     * Promote LOW priority jobs waiting more than 5 minutes.
     * Prevents CRITICAL jobs from starving LOW jobs indefinitely.
     */

    void promoteStaleJobs() {
        Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
        int promoted = jobRepository.promoteStalePendingJobs(fiveMinutesAgo);
        if (promoted > 0) {
            log.info("Anti-starvation: promoted {} jobs waiting >5 minutes", promoted);
        }
    }

    // ── Stats logging ─────────────────────────────────────────────────────────

    void logQueueStats() {
        // Logged at TRACE — only visible when explicitly enabled
        log.trace("Scheduler tick complete at {}", Instant.now());
    }
}
