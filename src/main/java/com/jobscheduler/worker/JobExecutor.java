package com.jobscheduler.worker;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobRun;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.domain.repository.JobRunRepository;
import com.jobscheduler.metrics.JobMetrics;
import com.jobscheduler.service.RetryPolicyService;
import com.jobscheduler.worker.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final RetryPolicyService retryPolicyService;
    private final JobMetrics jobMetrics;
    private final Map<String, JobHandler> handlerMap;

    public JobExecutor(JobRepository jobRepository,
                       JobRunRepository jobRunRepository,
                       RetryPolicyService retryPolicyService,
                       JobMetrics jobMetrics,
                       List<JobHandler> handlers) {
        this.jobRepository      = jobRepository;
        this.jobRunRepository   = jobRunRepository;
        this.retryPolicyService = retryPolicyService;
        this.jobMetrics         = jobMetrics;
        this.handlerMap = handlers.stream()
            .collect(Collectors.toMap(h -> h.getJobType().name(), Function.identity()));
    }

    public void execute(UUID jobId, String nodeId) {
        MDC.put("jobId", jobId.toString());
        try {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("Job {} not found — skipping", jobId);
                return;
            }
            if (job.getStatus() != JobStatus.RUNNING) {
                log.warn("Job {} is not RUNNING (status={}), skipping", jobId, job.getStatus());
                return;
            }

            JobHandler handler = handlerMap.get(job.getJobType().name());
            if (handler == null) {
                log.error("No handler found for job type: {}", job.getJobType());
                jobMetrics.recordFailure(job.getJobType(), "no_handler");
                retryPolicyService.handleFailure(job, nodeId, Instant.now(),
                    "No handler for job type: " + job.getJobType(), false);
                return;
            }

            log.info("Executing job {} (type={}, attempt={}/{})",
                jobId, job.getJobType(), job.getRetryCount() + 1, job.getMaxRetries() + 1);

            Instant startedAt = Instant.now();

            try {
                JobHandler.ExecutionResult result = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return handler.execute(job);
                        } catch (JobHandler.JobExecutionException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .orTimeout(job.getTimeoutSeconds(), TimeUnit.SECONDS)
                    .get();

                handleSuccess(job, nodeId, startedAt, result.output());

            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TimeoutException) {
                    log.warn("Job {} timed out after {}s", jobId, job.getTimeoutSeconds());
                    jobMetrics.recordFailure(job.getJobType(), "timeout");
                    retryPolicyService.handleFailure(job, nodeId, startedAt,
                        "Timed out after " + job.getTimeoutSeconds() + "s", true);
                } else {
                    String msg = cause != null ? cause.getMessage() : "Unknown error";
                    log.warn("Job {} failed: {}", jobId, msg);
                    jobMetrics.recordFailure(job.getJobType(), "error");
                    retryPolicyService.handleFailure(job, nodeId, startedAt, msg, false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Job {} execution interrupted", jobId);
                jobMetrics.recordFailure(job.getJobType(), "interrupted");
                retryPolicyService.handleFailure(job, nodeId, startedAt, "Execution interrupted", false);
            }
        } finally {
            MDC.remove("jobId");
        }
    }

    @Transactional
    protected void handleSuccess(Job job, String nodeId, Instant startedAt, String output) {
        long durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli();

        job.setStatus(JobStatus.COMPLETED);
        job.setWorkerId(null);
        job.setLastRunAt(Instant.now());
        job.setErrorMessage(null);
        jobRepository.save(job);

        JobRun run = JobRun.success(job, nodeId, startedAt, output);
        jobRunRepository.save(run);

        // ── Metrics ───────────────────────────────────────────────────────────
        jobMetrics.recordCompletion(job.getJobType(), durationMs);

        log.info("Job {} completed in {}ms", job.getId(), durationMs);
    }
}
