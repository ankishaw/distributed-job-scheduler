package com.jobscheduler.api.controller;

import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.service.JobService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API for job lifecycle management.
 *
 * Endpoints:
 *   POST   /api/v1/jobs              → 202 Accepted  + JobResponse
 *   GET    /api/v1/jobs/{id}         → 200 OK        + JobResponse
 *   GET    /api/v1/jobs?status=...   → 200 OK        + Page<JobResponse>
 *   DELETE /api/v1/jobs/{id}         → 204 No Content
 *
 * The controller never touches repositories directly.
 * All business logic lives in JobService.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    // ── POST /api/v1/jobs ─────────────────────────────────────────────────────

    /**
     * Submit a new job.
     *
     * Returns 202 Accepted (not 201) because the job is queued for execution,
     * not immediately processed. The client should poll GET /{id} for status.
     *
     * Example request body:
     * {
     *   "name": "send-welcome-email",
     *   "jobType": "WEBHOOK",
     *   "payload": { "url": "https://api.example.com/notify", "httpMethod": "POST" },
     *   "priority": "HIGH",
     *   "maxRetries": 3
     * }
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse submitJob(@Valid @RequestBody CreateJobRequest request) {
        return jobService.createJob(request);
    }

    // ── GET /api/v1/jobs/{id} ─────────────────────────────────────────────────

    /**
     * Get job status and details by ID.
     * Returns 404 if the job doesn't exist.
     */
    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable UUID id) {
        return jobService.getJob(id);
    }

    // ── GET /api/v1/jobs ──────────────────────────────────────────────────────

    /**
     * List jobs with optional status filter and pagination.
     *
     * Examples:
     *   GET /api/v1/jobs                          → all jobs, page 0, size 20
     *   GET /api/v1/jobs?status=PENDING           → pending only
     *   GET /api/v1/jobs?status=FAILED&page=1     → second page of failed jobs
     *   GET /api/v1/jobs?status=PENDING,RUNNING   → multiple statuses
     */
    @GetMapping
    public Page<JobResponse> listJobs(
            @RequestParam(required = false) List<JobStatus> status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return jobService.listJobs(status, pageable);
    }

    // ── DELETE /api/v1/jobs/{id} ──────────────────────────────────────────────

    /**
     * Cancel a job. Only PENDING and FAILED jobs can be cancelled.
     *
     * Returns 204 No Content on success.
     * Returns 409 Conflict if the job is RUNNING or in a terminal state.
     * Returns 404 if the job doesn't exist.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelJob(@PathVariable UUID id) {
        jobService.cancelJob(id);
    }
}
