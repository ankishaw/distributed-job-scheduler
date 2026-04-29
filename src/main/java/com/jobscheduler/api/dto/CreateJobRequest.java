package com.jobscheduler.api.dto;

import com.jobscheduler.domain.model.JobPriority;
import com.jobscheduler.domain.model.JobType;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Inbound DTO for POST /api/v1/jobs.
 *
 * Payload shape per jobType:
 *   WEBHOOK → { "url": "https://...", "httpMethod": "POST", "body": { ... } }
 *   SHELL   → { "command": "echo hello" }
 *
 * Scheduling rules:
 *   - cronExpression + runAt both null  → run immediately (nextRunAt = now)
 *   - cronExpression set               → recurring job
 *   - runAt set                        → one-shot delayed job
 *   - both set                         → 400 Bad Request (validated below)
 */
public class CreateJobRequest {

    @NotBlank(message = "name is required")
    @Size(max = 255, message = "name must be 255 characters or fewer")
    private String name;

    @NotNull(message = "jobType is required")
    private JobType jobType;

    @NotNull(message = "payload is required")
    private Map<String, Object> payload = new HashMap<>();

    /** Standard 6-field Spring cron expression. Null = one-shot job. */
    private String cronExpression;

    /** When to run this job. Null = run immediately. */
    private Instant runAt;

    private JobPriority priority = JobPriority.MEDIUM;

    @Min(value = 0, message = "maxRetries must be 0 or greater")
    @Max(value = 10, message = "maxRetries must be 10 or fewer")
    private int maxRetries = 3;

    @Min(value = 1, message = "timeoutSeconds must be at least 1")
    @Max(value = 3600, message = "timeoutSeconds must be 3600 or fewer")
    private int timeoutSeconds = 300;

    /**
     * Optional idempotency key — if set, the handler will skip execution
     * if a successful run with this key already exists.
     */
    @Size(max = 255, message = "idempotencyKey must be 255 characters or fewer")
    private String idempotencyKey;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public JobType getJobType() { return jobType; }
    public void setJobType(JobType jobType) { this.jobType = jobType; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }

    public JobPriority getPriority() { return priority; }
    public void setPriority(JobPriority priority) { this.priority = priority; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
