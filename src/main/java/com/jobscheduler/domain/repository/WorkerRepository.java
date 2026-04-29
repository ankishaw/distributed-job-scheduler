package com.jobscheduler.domain.repository;

import com.jobscheduler.domain.model.Worker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, String> {

    /**
     * Find all workers that haven't sent a heartbeat since {@code threshold}.
     * StaleJobReclaimer uses this: threshold = now() - staleWorkerThresholdSeconds.
     *
     * The idx_workers_last_seen index makes this an index scan, not a seq scan,
     * even with thousands of historical worker rows.
     */
    List<Worker> findByLastSeenBefore(Instant threshold);

    /**
     * Count workers that are currently alive — used by GET /health.
     * threshold = now() - staleWorkerThresholdSeconds.
     */
    int countByLastSeenAfter(Instant threshold);
}
