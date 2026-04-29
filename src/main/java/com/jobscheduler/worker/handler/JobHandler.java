package com.jobscheduler.worker.handler;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobType;

/**
 * Contract for all job execution handlers.
 *
 * Each JobType has exactly one @Component implementation.
 * WorkerPool builds a Map<JobType, JobHandler> at startup by injecting
 * List<JobHandler> and calling getJobType() on each.
 *
 * Adding a new job type:
 *   1. Add value to JobType enum
 *   2. Create a new @Component class implementing this interface
 *   No other wiring needed.
 */
public interface JobHandler {

    /** Which job type this handler executes. */
    JobType getJobType();

    /**
     * Execute the job and return a result.
     *
     * Throw JobExecutionException on any recoverable error.
     * The caller (JobExecutor) handles retries and status updates.
     *
     * This method runs inside a CompletableFuture with a timeout —
     * do NOT catch InterruptedException or TimeoutException here.
     */
    ExecutionResult execute(Job job) throws JobExecutionException;

    // ── Result record ─────────────────────────────────────────────────────────

    record ExecutionResult(String output, int statusCode) {
        public static ExecutionResult success(String output) {
            return new ExecutionResult(output, 0);
        }
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    class JobExecutionException extends Exception {
        public JobExecutionException(String message) {
            super(message);
        }
        public JobExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
