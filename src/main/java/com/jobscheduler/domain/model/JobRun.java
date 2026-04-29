package com.jobscheduler.domain.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable execution log — one row per execution attempt.
 * Never update after insert; append-only for full auditability.
 *
 * Use the static factory methods to create instances:
 *   JobRun.success(job, workerId, startedAt, output)
 *   JobRun.failure(job, workerId, startedAt, errorMessage)
 *   JobRun.timedOut(job, workerId, startedAt)
 */
@Entity
@Table(name = "job_runs")
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK to jobs.id — ON DELETE CASCADE in schema. */
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    /** NODE_ID of the worker that ran this attempt. */
    @Column(name = "worker_id", updatable = false)
    private String workerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false,
            columnDefinition = "run_status")
    private RunStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    @Column(name = "duration_ms", updatable = false)
    private Long durationMs;

    /** 1-based retry count — 1 means first attempt, 2 means first retry, etc. */
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "error_message", columnDefinition = "TEXT", updatable = false)
    private String errorMessage;

    /** stdout/stderr for SHELL, response body for WEBHOOK. */
    @Column(name = "output", columnDefinition = "TEXT", updatable = false)
    private String output;

    protected JobRun() {}

    // ── Static factories ──────────────────────────────────────────────────────

    public static JobRun success(Job job, String workerId, Instant startedAt, String output) {
        var run = new JobRun();
        run.jobId         = job.getId();
        run.workerId      = workerId;
        run.status        = RunStatus.SUCCESS;
        run.startedAt     = startedAt;
        run.finishedAt    = Instant.now();
        run.durationMs    = run.finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        run.attemptNumber = (short)(job.getRetryCount() + 1);
        run.output        = output;
        return run;
    }

    public static JobRun failure(Job job, String workerId, Instant startedAt, String errorMessage) {
        var run = new JobRun();
        run.jobId         = job.getId();
        run.workerId      = workerId;
        run.status        = RunStatus.FAILED;
        run.startedAt     = startedAt;
        run.finishedAt    = Instant.now();
        run.durationMs    = run.finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        run.attemptNumber = (short)(job.getRetryCount() + 1);
        run.errorMessage  = errorMessage;
        return run;
    }

    public static JobRun timedOut(Job job, String workerId, Instant startedAt) {
        var run = new JobRun();
        run.jobId         = job.getId();
        run.workerId      = workerId;
        run.status        = RunStatus.TIMED_OUT;
        run.startedAt     = startedAt;
        run.finishedAt    = Instant.now();
        run.durationMs    = run.finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        run.attemptNumber = (short)(job.getRetryCount() + 1);
        run.errorMessage  = "Execution exceeded timeout of " + job.getTimeoutSeconds() + "s";
        return run;
    }

    // ── Getters (no setters — immutable after creation) ───────────────────────

    public UUID getId()            { return id; }
    public UUID getJobId()         { return jobId; }
    public String getWorkerId()    { return workerId; }
    public RunStatus getStatus()   { return status; }
    public Instant getStartedAt()  { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs()    { return durationMs; }
    public int getAttemptNumber()  { return attemptNumber; }
    public String getErrorMessage(){ return errorMessage; }
    public String getOutput()      { return output; }
}
