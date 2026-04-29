package com.jobscheduler.api.exception;

import java.util.UUID;

/**
 * Thrown by JobService.getJob() and JobService.cancelJob() when no job
 * with the given ID exists. GlobalExceptionHandler maps this to HTTP 404.
 */
public class JobNotFoundException extends RuntimeException {

    private final UUID jobId;

    public JobNotFoundException(UUID jobId) {
        super("Job not found: " + jobId);
        this.jobId = jobId;
    }

    public UUID getJobId() { return jobId; }
}
