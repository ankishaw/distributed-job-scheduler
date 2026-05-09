package com.jobscheduler.queue;

import org.redisson.api.RBlockingDeque;
import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed job dispatch queue using Redisson blocking deque.
 *
 * Queue structure:
 *   jobs:pending    — job IDs waiting to be claimed (LPUSH / BRPOPLPUSH source)
 *   jobs:processing — job IDs currently being executed (BRPOPLPUSH destination)
 *
 * Flow:
 *   Submit:   LPUSH jobs:pending {jobId}
 *   Claim:    BRPOPLPUSH jobs:pending jobs:processing (blocks until job available)
 *   Complete: LREM jobs:processing {jobId}
 *   Timeout:  Job stays in jobs:processing → reclaimer moves it back to pending
 *
 * Why BRPOPLPUSH over LPOP:
 *   BRPOPLPUSH is atomic — if the worker crashes after claiming but before
 *   completing, the job ID remains in jobs:processing. The stale job reclaimer
 *   finds it there and re-queues it. No job is ever silently lost.
 *
 * Why this + SKIP LOCKED fallback:
 *   If Redis goes down, workers fall back to Postgres SKIP LOCKED — zero job loss.
 *   This is the two-layer fault tolerance pattern used by Sidekiq in production.
 */
@Component
public class RedisJobQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisJobQueue.class);

    private static final String PENDING_KEY    = "jobs:pending";
    private static final String PROCESSING_KEY = "jobs:processing";

    private final RedissonClient redisson;

    public RedisJobQueue(RedissonClient redisson) {
        this.redisson = redisson;
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * Push a job ID to the front of the pending queue (LPUSH).
     * Called by JobService.createJob() immediately after saving to Postgres.
     * Returns true if pushed successfully, false if Redis is unavailable.
     */
    public boolean push(UUID jobId) {
        try {
            RBlockingDeque<String> pending = redisson.getBlockingDeque(PENDING_KEY);
            pending.addFirst(jobId.toString());
            log.debug("Pushed job {} to Redis queue (pending size: {})",
                    jobId, pending.size());
            return true;
        } catch (Exception e) {
            log.warn("Failed to push job {} to Redis queue — SKIP LOCKED fallback: {}",
                    jobId, e.getMessage());
            return false;
        }
    }

    // ── Claim ─────────────────────────────────────────────────────────────────

    /**
     * Block until a job is available, then atomically move it from
     * jobs:pending to jobs:processing (BRPOPLPUSH).
     *
     * Returns the job ID if claimed within the timeout.
     * Returns empty if no job available or Redis is unavailable.
     *
     * The move to jobs:processing is the safety net — if the worker crashes,
     * the job ID stays in processing and the reclaimer re-queues it.
     *
     * @param timeoutSeconds how long to block waiting for a job
     */
    public Optional<UUID> claim(int timeoutSeconds) {
        try {
            RBlockingDeque<String> pending = redisson.getBlockingDeque(PENDING_KEY);
            // pollLastAndOfferFirstTo = BRPOPLPUSH from pending → processing
            String jobIdStr = pending.pollLastAndOfferFirstTo(
                    PROCESSING_KEY, timeoutSeconds, TimeUnit.SECONDS
            );
            if (jobIdStr != null) {
                UUID jobId = UUID.fromString(jobIdStr);
                log.debug("Claimed job {} from Redis queue → moved to processing", jobId);
                return Optional.of(jobId);
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis queue claim failed — will use SKIP LOCKED fallback: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    /**
     * Remove a job ID from jobs:processing after successful execution.
     * Called by JobExecutor after writing COMPLETED status to Postgres.
     */
    public void complete(UUID jobId) {
        try {
            RBlockingDeque<String> processing = redisson.getBlockingDeque(PROCESSING_KEY);
            boolean removed = processing.remove(jobId.toString());
            if (removed) {
                log.debug("Removed job {} from jobs:processing queue", jobId);
            } else {
                log.debug("Job {} was not in processing queue (already removed or SKIP LOCKED path)", jobId);
            }
        } catch (Exception e) {
            log.warn("Failed to remove job {} from processing queue: {}", jobId, e.getMessage());
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /**
     * Current pending queue depth — used by metrics and health endpoint.
     */
    public long pendingCount() {
        try {
            return redisson.getBlockingDeque(PENDING_KEY).size();
        } catch (Exception e) { return -1; }
    }

    /**
     * Current in-flight job count — jobs claimed but not yet completed.
     */
    public long processingCount() {
        try {
            return redisson.getBlockingDeque(PROCESSING_KEY).size();
        } catch (Exception e) { return -1; }
    }

    /**
     * Check if Redis is reachable. Used by WorkerPool to decide
     * whether to use Redis queue or fall back to SKIP LOCKED.
     */
    public boolean isAvailable() {
        try {
            redisson.getDeque(PENDING_KEY).size();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
