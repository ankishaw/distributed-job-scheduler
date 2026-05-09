package com.jobscheduler.domain.repository;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /**
     * SKIP LOCKED — claim up to `limit` PENDING jobs due now.
     * Atomically locks rows; other workers skip locked rows instantly.
     * Primary dispatch mechanism when Redis is unavailable.
     */
    @Query(value = """
            SELECT * FROM jobs
            WHERE  status = 'PENDING'
            AND    next_run_at <= :now
            ORDER  BY priority DESC, created_at ASC
            LIMIT  :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> claimPendingJobs(@Param("now") Instant now,
                               @Param("limit") int limit);

    /**
     * Atomically mark a job as RUNNING.
     * Used by the Redis queue claim path to prevent double-claiming.
     * Returns 1 if updated (success), 0 if already claimed by another worker.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE jobs
            SET    status    = :status,
                   worker_id = :workerId,
                   updated_at = now()
            WHERE  id     = :jobId
            AND    status = 'PENDING'
            """, nativeQuery = true)
    int markJobRunning(@Param("jobId") UUID jobId,
                       @Param("workerId") String workerId,
                       @Param("status") String status);

    /**
     * Find COMPLETED cron jobs to reschedule.
     * SchedulerLoop calls this every tick.
     */
    @Query("""
            SELECT j FROM Job j
            WHERE  j.status = 'COMPLETED'
            AND    j.cronExpression IS NOT NULL
            ORDER  BY j.updatedAt ASC
            """)
    Page<Job> findCompletedCronJobs(Pageable pageable);

    /**
     * Anti-starvation: bump priority for long-waiting jobs.
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

    /**
     * Reclaim RUNNING jobs from a dead worker back to PENDING.
     * Called by HeartbeatAndReclaimService when a worker's last_seen is stale.
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

    Page<Job> findByStatusIn(List<JobStatus> statuses, Pageable pageable);
}
