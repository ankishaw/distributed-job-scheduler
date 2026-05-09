package com.jobscheduler.worker;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.queue.RedisJobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JobClaimService {

    private static final Logger log = LoggerFactory.getLogger(JobClaimService.class);
    private static final int REDIS_TIMEOUT = 1;

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    private final JobRepository jobRepository;
    private final RedisJobQueue redisJobQueue;

    public JobClaimService(JobRepository jobRepository, RedisJobQueue redisJobQueue) {
        this.jobRepository = jobRepository;
        this.redisJobQueue = redisJobQueue;
    }

    @Transactional
    public UUID claimNextJob() {
        // Layer 1: Redis hot path
        Optional<UUID> redisJob = redisJobQueue.claim(REDIS_TIMEOUT);
        if (redisJob.isPresent()) {
            UUID jobId = redisJob.get();
            int updated = jobRepository.markJobRunning(
                    jobId, nodeId, JobStatus.RUNNING.name()
            );
            if (updated > 0) {
                log.debug("Claimed job {} via Redis queue", jobId);
                return jobId;
            } else {
                redisJobQueue.complete(jobId);
                log.debug("Job {} already claimed — skipping", jobId);
                return null;
            }
        }

        // Layer 2: SKIP LOCKED fallback
        List<Job> jobs = jobRepository.claimPendingJobs(Instant.now(), 1);
        if (jobs.isEmpty()) return null;

        Job job = jobs.get(0);
        job.setStatus(JobStatus.RUNNING);
        job.setWorkerId(nodeId);
        jobRepository.save(job);
        log.debug("Claimed job {} via SKIP LOCKED fallback", job.getId());
        return job.getId();
    }
}