package com.jobscheduler.scheduler;

import com.jobscheduler.config.AppProperties;
import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.leader.LeaderElectionService;
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
 * Scheduling brain — only runs on the elected leader node.
 *
 * LEADER GUARD: tick() returns immediately if this node is not the leader.
 * This prevents duplicate cron dispatch across multiple scheduler instances.
 */
@Component
public class SchedulerLoop {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);

    private final AppProperties           appProperties;
    private final JobRepository           jobRepository;
    private final CronExpressionEvaluator cronEvaluator;
    private final LeaderElectionService   leaderElectionService;
    private ScheduledExecutorService      scheduler;

    public SchedulerLoop(AppProperties appProperties,
                         JobRepository jobRepository,
                         CronExpressionEvaluator cronEvaluator,
                         LeaderElectionService leaderElectionService) {
        this.appProperties         = appProperties;
        this.jobRepository         = jobRepository;
        this.cronEvaluator         = cronEvaluator;
        this.leaderElectionService = leaderElectionService;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-loop");
            t.setDaemon(false);
            return t;
        });
        long intervalMs = appProperties.getPollIntervalMs();
        scheduler.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("SchedulerLoop started — polling every {}ms", intervalMs);
    }

    @PreDestroy
    public void stop() {
        log.info("SchedulerLoop shutting down");
        scheduler.shutdown();
        try { scheduler.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Transactional
    public void tick() {
        // ── LEADER GUARD ──────────────────────────────────────────────────────
        // Only the elected leader runs this logic.
        // Non-leaders skip entirely — no cron dispatch, no priority bumps.
        if (!leaderElectionService.isLeader()) {
            log.trace("Node {} is not leader — skipping tick", leaderElectionService.getNodeId());
            return;
        }

        try {
            rescheduleCronJobs();
            promoteStaleJobs();
        } catch (Exception e) {
            log.error("Error during scheduler tick — will retry next interval", e);
        }
    }

    void rescheduleCronJobs() {
        List<Job> jobs = jobRepository
            .findCompletedCronJobs(PageRequest.of(0, appProperties.getDispatchBatchSize()))
            .getContent();

        if (jobs.isEmpty()) return;

        log.debug("Leader {}: rescheduling {} cron jobs", leaderElectionService.getNodeId(), jobs.size());

        for (Job job : jobs) {
            cronEvaluator.nextRunAfter(job.getCronExpression(), ZonedDateTime.now())
                .ifPresentOrElse(nextRun -> {
                    job.setStatus(JobStatus.PENDING);
                    job.setNextRunAt(nextRun);
                    job.setRetryCount(0);
                    job.setErrorMessage(null);
                    job.setWorkerId(null);
                    jobRepository.save(job);
                    log.info("Cron job '{}' rescheduled → next run at {}", job.getName(), nextRun);
                }, () -> log.warn("Cron job '{}' has no future execution", job.getName()));
        }
    }

    void promoteStaleJobs() {
        Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
        int promoted = jobRepository.promoteStalePendingJobs(fiveMinutesAgo);
        if (promoted > 0) {
            log.info("Anti-starvation: promoted {} jobs waiting >5 minutes", promoted);
        }
    }
}
