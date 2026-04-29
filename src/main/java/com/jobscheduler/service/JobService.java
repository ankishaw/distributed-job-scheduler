package com.jobscheduler.service;

import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.api.exception.JobNotFoundException;
import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.metrics.JobMetrics;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class JobService {

    private final JobRepository jobRepository;
    private final JobMetrics jobMetrics;

    public JobService(JobRepository jobRepository, JobMetrics jobMetrics) {
        this.jobRepository = jobRepository;
        this.jobMetrics    = jobMetrics;
    }

    public JobResponse createJob(CreateJobRequest req) {
        if (req.getCronExpression() != null && req.getRunAt() != null) {
            throw new IllegalArgumentException("Specify either cronExpression or runAt, not both");
        }

        Job job = new Job();
        job.setName(req.getName());
        job.setJobType(req.getJobType());
        job.setPayload(req.getPayload());
        job.setCronExpression(req.getCronExpression());
        job.setStatus(JobStatus.PENDING);
        job.setPriority(req.getPriority().getValue());
        job.setMaxRetries(req.getMaxRetries());
        job.setTimeoutSeconds(req.getTimeoutSeconds());
        job.setIdempotencyKey(req.getIdempotencyKey());

        if (req.getRunAt() != null) {
            job.setNextRunAt(req.getRunAt());
        } else if (req.getCronExpression() != null) {
            job.setNextRunAt(nextCronRun(req.getCronExpression()));
        } else {
            job.setNextRunAt(Instant.now());
        }

        Job saved = jobRepository.save(job);
        jobMetrics.recordSubmit(saved.getJobType(), req.getPriority());
        return JobResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(UUID id) {
        return JobResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> listJobs(List<JobStatus> statuses, Pageable pageable) {
        Page<Job> page = (statuses == null || statuses.isEmpty())
            ? jobRepository.findAll(pageable)
            : jobRepository.findByStatusIn(statuses, pageable);
        return page.map(JobResponse::from);
    }

    public JobResponse cancelJob(UUID id) {
        Job job = findOrThrow(id);
        job.transitionTo(JobStatus.CANCELLED);
        Job saved = jobRepository.save(job);
        return JobResponse.from(saved);
    }

    private Job findOrThrow(UUID id) {
        return jobRepository.findById(id)
            .orElseThrow(() -> new JobNotFoundException(id));
    }

    private Instant nextCronRun(String expression) {
        try {
            CronExpression cron = CronExpression.parse(expression);
            ZonedDateTime next = cron.next(ZonedDateTime.now());
            if (next == null) throw new IllegalArgumentException("No future execution: " + expression);
            return next.toInstant();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + expression + " — " + e.getMessage());
        }
    }
}
