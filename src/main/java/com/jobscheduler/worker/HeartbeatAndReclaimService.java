package com.jobscheduler.worker;

import com.jobscheduler.config.AppProperties;
import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.Worker;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.domain.repository.WorkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Two scheduled tasks for fault detection:
 *
 * 1. HeartbeatService — updates last_seen every 10s so the reclaimer
 *    knows this worker is alive.
 *
 * 2. StaleJobReclaimer — runs every 30s, finds workers whose heartbeat
 *    has gone stale, reclaims their in-flight jobs back to PENDING,
 *    and removes the dead worker row.
 *
 * Combined in one file since they share the same beans.
 */
@Component
public class HeartbeatAndReclaimService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatAndReclaimService.class);

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    private final WorkerRepository workerRepository;
    private final JobRepository jobRepository;
    private final AppProperties appProperties;

    public HeartbeatAndReclaimService(WorkerRepository workerRepository,
                                       JobRepository jobRepository,
                                       AppProperties appProperties) {
        this.workerRepository = workerRepository;
        this.jobRepository    = jobRepository;
        this.appProperties    = appProperties;
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    /**
     * Update last_seen timestamp every 10 seconds.
     * If this node misses 3 heartbeats (30s), StaleJobReclaimer will
     * declare it dead and reclaim its jobs.
     */
    @Scheduled(fixedDelayString = "${scheduler.heartbeatIntervalMs:10000}")
    @Transactional
    public void sendHeartbeat() {
        workerRepository.findById(nodeId).ifPresent(worker -> {
            worker.touch();
            workerRepository.save(worker);
            log.debug("Heartbeat sent for node {}", nodeId);
        });
    }

    // ── Stale job reclaimer ───────────────────────────────────────────────────

    /**
     * Every 30s: find dead workers, reclaim their RUNNING jobs to PENDING.
     *
     * A worker is "dead" if last_seen < now() - staleWorkerThresholdSeconds.
     * This is the primary fault recovery mechanism — no manual intervention needed.
     */
    @Scheduled(fixedDelayString = "${scheduler.staleWorkerThresholdSeconds:30}000")
    @Transactional
    public void reclaimStaleJobs() {
        int thresholdSeconds = appProperties.getStaleWorkerThresholdSeconds();
        Instant threshold = Instant.now().minusSeconds(thresholdSeconds);

        List<Worker> deadWorkers = workerRepository.findByLastSeenBefore(threshold);

        for (Worker deadWorker : deadWorkers) {
            if (deadWorker.getNodeId().equals(nodeId)) {
                continue; // never reclaim our own jobs
            }

            int reclaimed = jobRepository.reclaimJobsForWorker(deadWorker.getNodeId());
            workerRepository.delete(deadWorker);

            if (reclaimed > 0) {
                log.warn("Reclaimed {} jobs from dead worker {} (last seen: {})",
                    reclaimed, deadWorker.getNodeId(), deadWorker.getLastSeen());
            } else {
                log.info("Removed dead worker {} with no in-flight jobs",
                    deadWorker.getNodeId());
            }
        }
    }
}
