package com.jobscheduler.api.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Simple health endpoint returning node identity and status.
 *
 * GET /health → { status, nodeId, timestamp }
 *
 * In Phase 3 (leader election), this will also return:
 *   isLeader, leaseTtlRemaining, activeWorkers, queueDepth
 *
 * Separate from Spring Actuator /actuator/health so we can include
 * scheduler-specific state in the response.
 */
@RestController
public class HealthController {

    @Value("${NODE_ID:local-dev}")
    private String nodeId;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status",    "UP",
            "nodeId",    nodeId,
            "timestamp", Instant.now().toString()
        );
    }
}
