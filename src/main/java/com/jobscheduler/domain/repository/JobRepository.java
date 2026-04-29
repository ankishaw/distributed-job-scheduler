package com.jobscheduler.domain.repository;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data access for the jobs table.
 *
 * The two most important queries are:
 *   1. claimPendingJobs  — SKIP LOCKED for parallel, contention-free claiming
 *   2. findDueJobs       — scheduler loop uses this to fill the Redis queue
 *
 * Both must be called from inside a @Transactional method.
 */
@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    // ── Worker: claim jobs from Postgres (Phase 1 — pre-Redis) ───────────────

    /**
     * Atomically claim up to {@code limit} PENDING jobs, skipping any row
     * currently locked by another transaction (SKIP LOCKED).
     *
     * The calling service MUST update status → RUNNING within the same
     * @Transactional boundary before the method returns. If it doesn't,
     * the lock is released on commit and another worker can claim the same rows.
     *
     * ORDER BY priority DESC, created_at ASC ensures CRITICAL jobs dispatch first,
     * with FIFO ordering within the same priority level.
     */
    @Query(value = """
            SELECT * FROM jobs
            WHERE  status = 'PENDING'
            AND    next_run_at <= :now
            ORDER  BY priority DESC, created_at ASC
            LIMIT  :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> claimPendingJobs(
            @Param("now")   Instant now,
            @Param("limit") int limit
    );

    // ── Scheduler: find jobs to enqueue into Redis ────────────────────────────

    /**
     * Find PENDING jobs whose next_run_at is in the past.
     * Used by SchedulerLoop to populate the Redis work queue.
     * Pageable controls the batch size (dispatchBatchSize from AppProperties).
     */
    @Query("""
            SELECT j FROM Job j
            WHERE  j.status = :status
            AND    j.nextRunAt <= :now
            ORDER  BY j.priority DESC, j.createdAt ASC
            """)
    Page<Job> findDueJobs(
            @Param("status") JobStatus status,
            @Param("now")    Instant now,
            Pageable pageable
    );

    // ── Stale job reclaim ─────────────────────────────────────────────────────

    /**
     * Move all RUNNING jobs owned by a dead worker back to PENDING.
     * Called by StaleJobReclaimer after confirming the worker's heartbeat has expired.
     *
     * Returns the number of rows updated (useful for metrics).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE jobs
            SET    status    = 'PENDING',
                   worker_id = NULL,
                   updated_at = now()
            WHERE  status    = 'RUNNING'
            AND    worker_id = :workerId
            """, nativeQuery = true)
    int reclaimJobsForWorker(@Param("workerId") String workerId);

    // ── Admin / API queries ───────────────────────────────────────────────────

    /** Paginated listing with optional status filter — used by GET /jobs. */
    Page<Job> findByStatusIn(List<JobStatus> statuses, Pageable pageable);

    /** All jobs in any state — for admin dashboards. */
    Page<Job> findAll(Pageable pageable);

    /**
     * Find completed recurring (cron) jobs that need rescheduling.
     * SchedulerLoop calls this every tick to compute and set the next run time.
     */
    @Query("""
            SELECT j FROM Job j
            WHERE  j.status = 'COMPLETED'
            AND    j.cronExpression IS NOT NULL
            ORDER  BY j.updatedAt ASC
            """)
    Page<Job> findCompletedCronJobs(Pageable pageable);

    /**
     * Anti-starvation: age-based priority promotion.
     * Bumps priority by 1 (capped at CRITICAL=4) for PENDING jobs
     * that have been waiting longer than the given threshold.
     *
     * Call this from a @Scheduled method every 5 minutes.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE jobs
            SET    priority   = LEAST(priority + 1, 4),
                   updated_at = now()
            WHERE  status     = 'PENDING'
            AND    next_run_at < :waitingBefore
            AND    priority   < 4
            """, nativeQuery = true)
    int promoteStalePendingJobs(@Param("waitingBefore") Instant waitingBefore);
}
