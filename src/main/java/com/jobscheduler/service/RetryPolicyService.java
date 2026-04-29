package com.jobscheduler.service;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobRun;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.domain.repository.JobRunRepository;
import com.jobscheduler.metrics.JobMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RetryPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicyService.class);

    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final JobMetrics jobMetrics;

    public RetryPolicyService(JobRepository jobRepository,
                               JobRunRepository jobRunRepository,
                               JobMetrics jobMetrics) {
        this.jobRepository    = jobRepository;
        this.jobRunRepository = jobRunRepository;
        this.jobMetrics       = jobMetrics;
    }

    @Transactional
    public void handleFailure(Job job, String workerId,
                               Instant startedAt, String errorMessage,
                               boolean timedOut) {
        // Always write a JobRun for every attempt
        JobRun run = timedOut
            ? JobRun.timedOut(job, workerId, startedAt)
            : JobRun.failure(job, workerId, startedAt, errorMessage);
        jobRunRepository.save(run);

        if (job.getRetryCount() < job.getMaxRetries()) {
            int newRetryCount = job.getRetryCount() + 1;
            long backoffSeconds = (long) Math.pow(2, newRetryCount);
            Instant nextRunAt = Instant.now().plusSeconds(backoffSeconds);

            job.setRetryCount(newRetryCount);
            job.setNextRunAt(nextRunAt);
            job.setWorkerId(null);
            job.setErrorMessage(errorMessage);
            job.setStatus(JobStatus.PENDING);
            jobRepository.save(job);

            // ── Metrics ───────────────────────────────────────────────────────
            jobMetrics.recordRetry(job.getJobType());

            log.warn("Job {} failed (attempt {}/{}). Retrying in {}s",
                job.getId(), newRetryCount, job.getMaxRetries(), backoffSeconds);
        } else {
            job.setStatus(JobStatus.DEAD_LETTER);
            job.setWorkerId(null);
            job.setErrorMessage("Max retries exhausted. Last error: " + errorMessage);
            jobRepository.save(job);

            // ── Metrics ───────────────────────────────────────────────────────
            jobMetrics.recordDeadLetter(job.getJobType());

            log.error("Job {} moved to DEAD_LETTER after {} retries",
                job.getId(), job.getMaxRetries());
        }
    }
}
