package com.jobscheduler.worker.handler;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * Executes WEBHOOK jobs — sends an HTTP POST to the URL in the payload.
 *
 * Expected payload shape:
 * {
 *   "url":        "https://api.example.com/notify",
 *   "httpMethod": "POST",          (optional, defaults to POST)
 *   "body":       { ... }          (optional request body)
 * }
 *
 * Throws JobExecutionException on non-2xx response or network error.
 */
@Component
public class WebhookJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookJobHandler.class);

    private final RestClient restClient;

    public WebhookJobHandler() {
        this.restClient = RestClient.builder()
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("User-Agent", "JobScheduler/1.0")
            .build();
    }

    @Override
    public JobType getJobType() {
        return JobType.WEBHOOK;
    }

    @Override
    public ExecutionResult execute(Job job) throws JobExecutionException {
        Map<String, Object> payload = job.getPayload();

        String url = (String) payload.get("url");
        if (url == null || url.isBlank()) {
            throw new JobExecutionException("Payload missing required field: url");
        }

        Object body = payload.getOrDefault("body", Map.of());

        log.debug("Executing WEBHOOK job {} → POST {}", job.getId(), url);

        try {
            String response = restClient.post()
                .uri(url)
                .body(body)
                .retrieve()
                .body(String.class);

            String output = response != null ? response : "(empty response)";
            log.info("WEBHOOK job {} completed successfully", job.getId());
            return ExecutionResult.success(output);

        } catch (RestClientException e) {
            throw new JobExecutionException(
                "HTTP request failed for URL " + url + ": " + e.getMessage(), e
            );
        }
    }
}
