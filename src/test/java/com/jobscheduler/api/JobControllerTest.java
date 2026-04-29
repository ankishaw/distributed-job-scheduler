package com.jobscheduler.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobscheduler.api.controller.JobController;
import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.api.exception.GlobalExceptionHandler;
import com.jobscheduler.api.exception.JobNotFoundException;
import com.jobscheduler.domain.model.JobPriority;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.model.JobType;
import com.jobscheduler.service.JobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for JobController — tests HTTP contract only.
 * No DB, no Redis, no workers. Pure MockMvc.
 */
@WebMvcTest(JobController.class)
@Import(GlobalExceptionHandler.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JobService jobService;

    // ── POST /api/v1/jobs ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /jobs with valid body → 202 Accepted + JobResponse")
    void submitJobShouldReturn202() throws Exception {
        CreateJobRequest request = validRequest();
        JobResponse response = buildResponse(UUID.randomUUID(), JobStatus.PENDING);

        when(jobService.createJob(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.jobType").value("SHELL"))
            .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    @DisplayName("POST /jobs with empty body → 400 Bad Request + fieldErrors")
    void submitJobWithEmptyBodyShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors").isArray())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /jobs missing name → 400 with name field error")
    void submitJobMissingNameShouldReturn400() throws Exception {
        CreateJobRequest request = validRequest();
        request.setName(null);

        mockMvc.perform(post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists());
    }

    // ── GET /api/v1/jobs/{id} ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET /jobs/{id} with known ID → 200 + JobResponse")
    void getJobShouldReturn200() throws Exception {
        UUID id = UUID.randomUUID();
        JobResponse response = buildResponse(id, JobStatus.COMPLETED);
        when(jobService.getJob(id)).thenReturn(response);

        mockMvc.perform(get("/api/v1/jobs/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /jobs/{id} with unknown ID → 404 Not Found")
    void getJobWithUnknownIdShouldReturn404() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.getJob(id)).thenThrow(new JobNotFoundException(id));

        mockMvc.perform(get("/api/v1/jobs/" + id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("Job not found: " + id));
    }

    // ── DELETE /api/v1/jobs/{id} ──────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /jobs/{id} → 204 No Content")
    void cancelJobShouldReturn204() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.cancelJob(id)).thenReturn(buildResponse(id, JobStatus.CANCELLED));

        mockMvc.perform(delete("/api/v1/jobs/" + id))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /jobs/{id} on RUNNING job → 409 Conflict")
    void cancelRunningJobShouldReturn409() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.cancelJob(id))
            .thenThrow(new IllegalStateException("Invalid job state transition: RUNNING → CANCELLED"));

        mockMvc.perform(delete("/api/v1/jobs/" + id))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CreateJobRequest validRequest() {
        CreateJobRequest req = new CreateJobRequest();
        req.setName("test-job");
        req.setJobType(JobType.SHELL);
        req.setPayload(Map.of("command", "echo hello"));
        req.setPriority(JobPriority.MEDIUM);
        req.setMaxRetries(3);
        req.setTimeoutSeconds(30);
        return req;
    }

    private JobResponse buildResponse(UUID id, JobStatus status) {
        // Use reflection to build response since setters aren't public
        JobResponse r = new JobResponse();
        try {
            set(r, "id", id);
            set(r, "name", "test-job");
            set(r, "status", status);
            set(r, "jobType", JobType.SHELL);
            set(r, "priority", JobPriority.MEDIUM);
            set(r, "retryCount", 0);
            set(r, "maxRetries", 3);
            set(r, "createdAt", Instant.now());
            set(r, "updatedAt", Instant.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    private void set(Object obj, String field, Object value) throws Exception {
        var f = obj.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(obj, value);
    }
}
