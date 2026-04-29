package com.jobscheduler.integration;

import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.model.JobType;
import com.jobscheduler.domain.model.JobPriority;
import com.jobscheduler.domain.repository.JobRunRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the full job execution pipeline.
 *
 * Uses real Postgres and Redis (must be running via Docker Compose or local).
 * Does NOT use Testcontainers to keep it fast for local dev.
 *
 * To run: mvn failsafe:integration-test
 * Or:     right-click in IntelliJ → Run 'JobSubmitFlowIT'
 *
 * What this proves:
 *   POST /jobs → worker claims → executes → COMPLETED → job_runs row exists
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "scheduler", "worker"})
class JobSubmitFlowIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JobRunRepository jobRunRepository;

    // ── Test 1: Shell job completes successfully ──────────────────────────────

    @Test
    @DisplayName("SHELL job submitted → COMPLETED within 10 seconds → job_run recorded")
    void shellJobShouldCompleteAndRecordJobRun() {
        // ── Arrange ───────────────────────────────────────────────────────────
        CreateJobRequest request = new CreateJobRequest();
        request.setName("integration-test-shell");
        request.setJobType(JobType.SHELL);
        request.setPayload(Map.of("command", "echo integration test passed"));
        request.setPriority(JobPriority.HIGH);
        request.setMaxRetries(0);
        request.setTimeoutSeconds(10);

        // ── Act: submit the job ───────────────────────────────────────────────
        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/jobs",
            request,
            JobResponse.class
        );

        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(submitResponse.getBody()).isNotNull();

        UUID jobId = submitResponse.getBody().getId();
        assertThat(jobId).isNotNull();
        assertThat(submitResponse.getBody().getStatus()).isEqualTo(JobStatus.PENDING);

        // ── Assert: job completes within 10 seconds ───────────────────────────
        Awaitility.await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                ResponseEntity<JobResponse> statusResponse = restTemplate.getForEntity(
                    "http://localhost:" + port + "/api/v1/jobs/" + jobId,
                    JobResponse.class
                );
                assertThat(statusResponse.getBody()).isNotNull();
                assertThat(statusResponse.getBody().getStatus())
                    .isEqualTo(JobStatus.COMPLETED);
            });

        // ── Assert: a JobRun row was written ─────────────────────────────────
        var runs = jobRunRepository.findByJobIdOrderByStartedAtDesc(
            jobId, org.springframework.data.domain.Pageable.ofSize(10)
        );
        assertThat(runs.getContent()).hasSize(1);
        assertThat(runs.getContent().get(0).getStatus().name()).isEqualTo("SUCCESS");
        assertThat(runs.getContent().get(0).getOutput()).contains("integration test passed");
    }

    // ── Test 2: Invalid request returns 400 ──────────────────────────────────

    @Test
    @DisplayName("POST /jobs with empty body → 400 Bad Request with field errors")
    void emptyBodyShouldReturn400() {
        CreateJobRequest empty = new CreateJobRequest();

        ResponseEntity<String> response = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/jobs",
            empty,
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("fieldErrors");
    }

    // ── Test 3: Unknown job ID returns 404 ───────────────────────────────────

    @Test
    @DisplayName("GET /jobs/{unknown-id} → 404 Not Found")
    void unknownJobIdShouldReturn404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "http://localhost:" + port + "/api/v1/jobs/" + UUID.randomUUID(),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Test 4: Retry mechanism — failing job reaches DEAD_LETTER ────────────

    @Test
    @DisplayName("Failing SHELL job retries then moves to DEAD_LETTER")
    void failingJobShouldReachDeadLetterAfterRetries() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("integration-test-failing");
        request.setJobType(JobType.SHELL);
        request.setPayload(Map.of("command", "exit 1"));
        request.setPriority(JobPriority.CRITICAL);
        request.setMaxRetries(1); // 1 retry = 2 total attempts
        request.setTimeoutSeconds(5);

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/jobs",
            request,
            JobResponse.class
        );

        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = submitResponse.getBody().getId();

        // Wait for DEAD_LETTER — retry backoff is 2s so allow 20s total
        Awaitility.await()
            .atMost(20, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ResponseEntity<JobResponse> statusResponse = restTemplate.getForEntity(
                    "http://localhost:" + port + "/api/v1/jobs/" + jobId,
                    JobResponse.class
                );
                assertThat(statusResponse.getBody().getStatus())
                    .isEqualTo(JobStatus.DEAD_LETTER);
            });

        // Assert 2 JobRun records (1 original + 1 retry)
        var runs = jobRunRepository.findByJobIdOrderByStartedAtDesc(
            jobId, org.springframework.data.domain.Pageable.ofSize(10)
        );
        assertThat(runs.getContent()).hasSize(2);
    }

    // ── Test 5: Cancel a pending job ─────────────────────────────────────────

    @Test
    @DisplayName("Cancel PENDING delayed job → 204 → status CANCELLED")
    void cancelPendingJobShouldSucceed() {
        // Submit a delayed job far in the future so it stays PENDING
        CreateJobRequest request = new CreateJobRequest();
        request.setName("integration-test-cancel");
        request.setJobType(JobType.SHELL);
        request.setPayload(Map.of("command", "echo should not run"));
        request.setPriority(JobPriority.LOW);
        request.setMaxRetries(0);
        request.setTimeoutSeconds(5);
        request.setRunAt(java.time.Instant.now().plusSeconds(3600)); // 1 hour later

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/jobs",
            request,
            JobResponse.class
        );
        UUID jobId = submitResponse.getBody().getId();

        // Cancel it
        restTemplate.delete("http://localhost:" + port + "/api/v1/jobs/" + jobId);

        // Assert it's CANCELLED
        ResponseEntity<JobResponse> statusResponse = restTemplate.getForEntity(
            "http://localhost:" + port + "/api/v1/jobs/" + jobId,
            JobResponse.class
        );
        assertThat(statusResponse.getBody().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }
}
