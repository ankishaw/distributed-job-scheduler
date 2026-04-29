package com.jobscheduler.domain.repository;

import com.jobscheduler.domain.model.JobRun;
import com.jobscheduler.domain.model.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface JobRunRepository extends JpaRepository<JobRun, UUID> {

    /**
     * Full execution history for a job — most recent first.
     * Used by GET /api/v1/jobs/:id/runs.
     */
    Page<JobRun> findByJobIdOrderByStartedAtDesc(UUID jobId, Pageable pageable);

    /**
     * Count previous failures without loading all JobRun rows into memory.
     * Used by RetryPolicyService to decide whether to retry or move to DEAD_LETTER.
     */
    int countByJobIdAndStatus(UUID jobId, RunStatus status);

    /**
     * Check idempotency: has this job already completed successfully?
     * The handler calls this before executing when the job has an idempotencyKey.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM JobRun r
            JOIN Job j ON r.jobId = j.id
            WHERE j.idempotencyKey = :idempotencyKey
            AND   r.status = 'SUCCESS'
            """)
    boolean existsSuccessfulRunForIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
