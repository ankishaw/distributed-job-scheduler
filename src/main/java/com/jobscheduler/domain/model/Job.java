package com.jobscheduler.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core job entity — persisted in the jobs table.
 *
 * Design notes:
 *
 * - payload is stored as jsonb (flexible, no schema change needed for new job types)
 * - status uses @Enumerated(STRING) mapped to the job_status Postgres enum
 * - jobType uses @Enumerated(STRING) mapped to the job_type Postgres enum
 * - priority is stored as a plain int — JobPriority.getValue() on write,
 *   JobPriority.fromValue() on read
 * - No @Version for optimistic locking — SKIP LOCKED handles concurrency at
 *   the DB level; we don't need application-level OCC here
 *
 * Never expose this entity directly from the API layer — always map to JobResponse DTO.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false,
            columnDefinition = "job_type")
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "job_status")
    private JobStatus status = JobStatus.PENDING;

    /**
     * Flexible jsonb payload — shape depends on jobType:
     *   WEBHOOK → { url, httpMethod, body }
     *   SHELL   → { command }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload = new HashMap<>();

    // ── Scheduling ────────────────────────────────────────────────────────────

    /** Standard 6-field cron (Spring format). Null = one-shot job. */
    @Column(name = "cron_expression")
    private String cronExpression;

    /** When this job should next run. Set to now() on submit for immediate jobs. */
    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt = Instant.now();

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    // ── Execution config ──────────────────────────────────────────────────────

    /**
     * Raw int stored in DB — use JobPriority.getValue() / fromValue().
     * Default: MEDIUM (2). Higher = dispatched sooner.
     */
    @Column(nullable = false)
    private int priority = (JobPriority.MEDIUM.getValue());

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 300;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    // ── Fault tracking ────────────────────────────────────────────────────────

    /** NODE_ID of the worker currently executing this job. Null when not RUNNING. */
    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ── Idempotency ───────────────────────────────────────────────────────────

    /**
     * Optional unique key — if set, the handler checks for a completed JobRun
     * with this key before executing (prevents double-effects on re-delivery).
     */
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    public JobPriority getPriorityEnum() {
        return JobPriority.fromValue(this.priority);
    }

    public void setPriorityEnum(JobPriority p) {
        this.priority = (short)(p.getValue());
    }

    /**
     * Transition status, enforcing the state machine.
     * Throws IllegalStateException on an invalid transition.
     */
    public void transitionTo(JobStatus next) {
        this.status.assertCanTransitionTo(next);
        this.status = next;
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public JobType getJobType() { return jobType; }
    public void setJobType(JobType jobType) { this.jobType = jobType; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }

    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
