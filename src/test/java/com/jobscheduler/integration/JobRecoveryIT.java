package com.jobscheduler.integration;

import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.domain.model.JobPriority;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.model.JobType;
import com.jobscheduler.domain.repository.JobRunRepository;
import com.jobscheduler.domain.repository.JobRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for job execution, recovery, and HA behaviour.
 *
 * Tests prove:
 *  1. Jobs submitted via REST execute and reach COMPLETED
 *  2. Exactly one job_run is written per execution (no duplicates)
 *  3. Failing jobs reach DEAD_LETTER after retries
 *  4. Redis queue dispatches jobs (hot path)
 *  5. Jobs submitted with runAt stay PENDING until due
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "scheduler", "worker", "test"})
class JobRecoveryIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JobRunRepository jobRunRepository;

    @Autowired
    private JobRepository jobRepository;

    private String base() {
        return "http://localhost:" + port + "/api/v1/jobs";
    }

    // ── Test 1: SHELL job completes with exactly one job_run ─────────────────

    @Test
    @DisplayName("SHELL job → COMPLETED within 10s → exactly one job_run written")
    void shellJobShouldCompleteWithSingleJobRun() {
        // Submit
        CreateJobRequest req = shellRequest("ha-recovery-shell",
            "echo HA recovery test", JobPriority.CRITICAL, 0);

        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            base(), req, JobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = response.getBody().getId();

        // Wait for COMPLETED
        Awaitility.await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                JobResponse status = restTemplate.getForObject(
                    base() + "/" + jobId, JobResponse.class
                );
                assertThat(status.getStatus()).isEqualTo(JobStatus.COMPLETED);
            });

        // Assert exactly ONE job_run — no duplicate execution
        var runs = jobRunRepository.findByJobIdOrderByStartedAtDesc(
            jobId, Pageable.ofSize(10)
        );
        assertThat(runs.getContent())
            .as("Exactly one job_run should be written — no duplicate execution")
            .hasSize(1);
        assertThat(runs.getContent().get(0).getStatus().name()).isEqualTo("SUCCESS");
    }

    // ── Test 2: Failing job reaches DEAD_LETTER after retries ────────────────

    @Test
    @DisplayName("Failing SHELL job → DEAD_LETTER after maxRetries → correct job_run count")
    void failingJobShouldReachDeadLetterWithCorrectRunCount() {
        CreateJobRequest req = shellRequest("ha-failing-job", "exit 1",
            JobPriority.CRITICAL, 1); // maxRetries=1 → 2 total attempts

        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            base(), req, JobResponse.class
        );
        UUID jobId = response.getBody().getId();

        // Wait for DEAD_LETTER (retry backoff = 2s, total ~6s)
        Awaitility.await()
            .atMost(20, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                JobResponse status = restTemplate.getForObject(
                    base() + "/" + jobId, JobResponse.class
                );
                assertThat(status.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
            });

        // Assert 2 job_runs (1 original + 1 retry)
        var runs = jobRunRepository.findByJobIdOrderByStartedAtDesc(
            jobId, Pageable.ofSize(10)
        );
        assertThat(runs.getContent())
            .as("Should have 2 job_runs — original attempt + 1 retry")
            .hasSize(2);

        // Both should be FAILED
        assertThat(runs.getContent())
            .allMatch(r -> r.getStatus().name().equals("FAILED"));
    }

    // ── Test 3: Delayed job stays PENDING until due ───────────────────────────

    @Test
    @DisplayName("Delayed job stays PENDING until runAt — not dispatched immediately")
    void delayedJobShouldStayPendingUntilDue() {
        CreateJobRequest req = shellRequest("ha-delayed-job",
            "echo delayed", JobPriority.LOW, 0);
        req.setRunAt(java.time.Instant.now().plusSeconds(3600)); // 1 hour later

        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            base(), req, JobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = response.getBody().getId();

        // Should remain PENDING — not dispatched to Redis queue
        // Wait 3 seconds and confirm still PENDING
        try { Thread.sleep(3000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        JobResponse status = restTemplate.getForObject(
            base() + "/" + jobId, JobResponse.class
        );
        assertThat(status.getStatus())
            .as("Delayed job should stay PENDING until runAt")
            .isEqualTo(JobStatus.PENDING);

        // Cancel it so it doesn't linger in the DB
        restTemplate.delete(base() + "/" + jobId);
    }

    // ── Test 4: WEBHOOK job via Redis hot path ────────────────────────────────

    @Test
    @DisplayName("WEBHOOK job dispatched via Redis hot path → COMPLETED")
    void webhookJobShouldCompleteViaRedisQueue() {
        CreateJobRequest req = new CreateJobRequest();
        req.setName("ha-webhook-test");
        req.setJobType(JobType.WEBHOOK);
        req.setPayload(Map.of(
            "url", "https://httpbin.org/post",
            "httpMethod", "POST",
            "body", Map.of("source", "ha-integration-test")
        ));
        req.setPriority(JobPriority.CRITICAL);
        req.setMaxRetries(1);
        req.setTimeoutSeconds(15);

        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            base(), req, JobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = response.getBody().getId();

        Awaitility.await()
            .atMost(20, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                JobResponse status = restTemplate.getForObject(
                    base() + "/" + jobId, JobResponse.class
                );
                assertThat(status.getStatus()).isEqualTo(JobStatus.COMPLETED);
            });

        // Exactly one successful job_run
        var runs = jobRunRepository.findByJobIdOrderByStartedAtDesc(
            jobId, Pageable.ofSize(10)
        );
        assertThat(runs.getContent()).hasSize(1);
        assertThat(runs.getContent().get(0).getStatus().name()).isEqualTo("SUCCESS");
    }

    // ── Test 5: Cancel pending job ────────────────────────────────────────────

    @Test
    @DisplayName("Cancel PENDING delayed job → status CANCELLED, no execution")
    void cancelPendingJobShouldPreventExecution() {
        CreateJobRequest req = shellRequest("ha-cancel-test",
            "echo should not run", JobPriority.LOW, 0);
        req.setRunAt(java.time.Instant.now().plusSeconds(3600));

        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            base(), req, JobResponse.class
        );
        UUID jobId = response.getBody().getId();

        // Cancel it
        restTemplate.delete(base() + "/" + jobId);

        // Assert CANCELLED
        JobResponse status = restTemplate.getForObject(
            base() + "/" + jobId, JobResponse.class
        );
        assertThat(status.getStatus()).isEqualTo(JobStatus.CANCELLED);

        // No job_runs should exist — it was never executed
        var runs = jobRunRepository.findByJobIdOrderByStartedAtDesc(
            jobId, Pageable.ofSize(10)
        );
        assertThat(runs.getContent())
            .as("Cancelled job should have zero job_runs")
            .isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CreateJobRequest shellRequest(String name, String command,
                                          JobPriority priority, int maxRetries) {
        CreateJobRequest req = new CreateJobRequest();
        req.setName(name);
        req.setJobType(JobType.SHELL);
        req.setPayload(Map.of("command", command));
        req.setPriority(priority);
        req.setMaxRetries(maxRetries);
        req.setTimeoutSeconds(10);
        return req;
    }
}
