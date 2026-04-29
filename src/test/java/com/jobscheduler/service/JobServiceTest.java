package com.jobscheduler.service;

import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.api.exception.JobNotFoundException;
import com.jobscheduler.domain.model.*;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.metrics.JobMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobService.
 * No Spring context — pure Mockito, fast.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobMetrics jobMetrics;

    @InjectMocks
    private JobService jobService;

    private CreateJobRequest validRequest;

    @BeforeEach
    void setup() {
        validRequest = new CreateJobRequest();
        validRequest.setName("test-job");
        validRequest.setJobType(JobType.SHELL);
        validRequest.setPayload(Map.of("command", "echo hello"));
        validRequest.setPriority(JobPriority.MEDIUM);
        validRequest.setMaxRetries(3);
        validRequest.setTimeoutSeconds(30);
    }

    // ── createJob ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createJob → returns PENDING response with correct fields")
    void createJobShouldReturnPendingResponse() {
        Job savedJob = stubSave();

        JobResponse response = jobService.createJob(validRequest);

        assertThat(response.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(response.getName()).isEqualTo("test-job");
        assertThat(response.getJobType()).isEqualTo(JobType.SHELL);
        verify(jobRepository).save(any(Job.class));
        verify(jobMetrics).recordSubmit(JobType.SHELL, JobPriority.MEDIUM);
    }

    @Test
    @DisplayName("createJob with both cronExpression and runAt → throws IllegalArgumentException")
    void createJobWithBothCronAndRunAtShouldThrow() {
        validRequest.setCronExpression("0 * * * * *");
        validRequest.setRunAt(java.time.Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> jobService.createJob(validRequest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Specify either cronExpression or runAt");
    }

    @Test
    @DisplayName("createJob with invalid cron expression → throws IllegalArgumentException")
    void createJobWithInvalidCronShouldThrow() {
        validRequest.setCronExpression("not-a-cron");

        assertThatThrownBy(() -> jobService.createJob(validRequest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid cron expression");
    }

    // ── getJob ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getJob with unknown ID → throws JobNotFoundException")
    void getJobWithUnknownIdShouldThrow() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getJob(id))
            .isInstanceOf(JobNotFoundException.class)
            .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("getJob with known ID → returns correct response")
    void getJobWithKnownIdShouldReturnResponse() {
        UUID id = UUID.randomUUID();
        Job job = buildJob(id, JobStatus.PENDING);
        when(jobRepository.findById(id)).thenReturn(Optional.of(job));

        JobResponse response = jobService.getJob(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo(JobStatus.PENDING);
    }

    // ── cancelJob ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelJob on PENDING job → status becomes CANCELLED")
    void cancelPendingJobShouldSucceed() {
        UUID id = UUID.randomUUID();
        Job job = buildJob(id, JobStatus.PENDING);
        when(jobRepository.findById(id)).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenReturn(job);

        JobResponse response = jobService.cancelJob(id);

        assertThat(response.getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelJob on RUNNING job → throws IllegalStateException (409)")
    void cancelRunningJobShouldThrow() {
        UUID id = UUID.randomUUID();
        Job job = buildJob(id, JobStatus.RUNNING);
        when(jobRepository.findById(id)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobService.cancelJob(id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RUNNING");
    }

    @Test
    @DisplayName("cancelJob on COMPLETED job → throws IllegalStateException (409)")
    void cancelCompletedJobShouldThrow() {
        UUID id = UUID.randomUUID();
        Job job = buildJob(id, JobStatus.COMPLETED);
        when(jobRepository.findById(id)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobService.cancelJob(id))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Job stubSave() {
        Job job = buildJob(UUID.randomUUID(), JobStatus.PENDING);
        when(jobRepository.save(any(Job.class))).thenReturn(job);
        return job;
    }

    private Job buildJob(UUID id, JobStatus status) {
        Job job = new Job();
        // Use reflection to set the ID (it's not settable directly)
        try {
            var field = Job.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(job, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        job.setName("test-job");
        job.setJobType(JobType.SHELL);
        job.setStatus(status);
        job.setPriority(JobPriority.MEDIUM.getValue());
        job.setMaxRetries(3);
        job.setTimeoutSeconds(30);
        job.setPayload(Map.of("command", "echo hello"));
        return job;
    }
}
