package com.jobscheduler.api.controller;

import com.jobscheduler.leader.LeaderElectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health endpoint exposing node identity and leader status.
 *
 * GET /health → {
 *   status:       "UP",
 *   nodeId:       "local-1",
 *   isLeader:     true,
 *   leaderNodeId: "local-1",
 *   timestamp:    "2026-..."
 * }
 *
 * Use this during the HA demo to see which node is leader in real time.
 * Kill the leader → poll /health on the standby → watch isLeader flip to true.
 */
@RestController
public class HealthController {

    @Value("${NODE_ID:local-dev}")
    private String nodeId;

    private final LeaderElectionService leaderElectionService;

    public HealthController(LeaderElectionService leaderElectionService) {
        this.leaderElectionService = leaderElectionService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status",       "UP",
            "nodeId",       nodeId,
            "isLeader",     leaderElectionService.isLeader(),
            "leaderNodeId", leaderElectionService.getLeaderNodeId(),
            "timestamp",    Instant.now().toString()
        );
    }
}
