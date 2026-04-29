package com.jobscheduler.api.dto;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobPriority;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.model.JobType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound DTO — never expose the JPA entity directly from the API layer.
 *
 * Use the static factory: JobResponse.from(job)
 */
public class JobResponse {

    private UUID id;
    private String name;
    private JobStatus status;
    private JobType jobType;
    private JobPriority priority;
    private Map<String, Object> payload;
    private String cronExpression;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private int retryCount;
    private int maxRetries;
    private String errorMessage;
    private String idempotencyKey;
    private Instant createdAt;
    private Instant updatedAt;

    // ── Static factory ────────────────────────────────────────────────────────

    /**
     * Map a Job entity to its API response.
     * This is the only place the entity-to-DTO mapping lives.
     */
    public static JobResponse from(Job job) {
        JobResponse r = new JobResponse();
        r.id             = job.getId();
        r.name           = job.getName();
        r.status         = job.getStatus();
        r.jobType        = job.getJobType();
        r.priority       = JobPriority.fromValue(job.getPriority());
        r.payload        = job.getPayload();
        r.cronExpression = job.getCronExpression();
        r.nextRunAt      = job.getNextRunAt();
        r.lastRunAt      = job.getLastRunAt();
        r.retryCount     = job.getRetryCount();
        r.maxRetries     = job.getMaxRetries();
        r.errorMessage   = job.getErrorMessage();
        r.idempotencyKey = job.getIdempotencyKey();
        r.createdAt      = job.getCreatedAt();
        r.updatedAt      = job.getUpdatedAt();
        return r;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()              { return id; }
    public String getName()          { return name; }
    public JobStatus getStatus()     { return status; }
    public JobType getJobType()      { return jobType; }
    public JobPriority getPriority() { return priority; }
    public Map<String, Object> getPayload() { return payload; }
    public String getCronExpression(){ return cronExpression; }
    public Instant getNextRunAt()    { return nextRunAt; }
    public Instant getLastRunAt()    { return lastRunAt; }
    public int getRetryCount()       { return retryCount; }
    public int getMaxRetries()       { return maxRetries; }
    public String getErrorMessage()  { return errorMessage; }
    public String getIdempotencyKey(){ return idempotencyKey; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getUpdatedAt()    { return updatedAt; }
}
