package com.jobscheduler.integration;

import com.jobscheduler.leader.LeaderElectionService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for leader election and HA failover.
 *
 * IMPORTANT: Run these tests with the main app STOPPED.
 * If the main app is running, it holds the Redis leader key
 * and the test context cannot win the election.
 *
 * All timeouts are generous (retry interval = 5s, so timeouts = 12-20s).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "scheduler", "worker", "test"})
class LeaderElectionIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LeaderElectionService leaderElectionService;

    @Autowired
    private RedissonClient redissonClient;

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    private static final String LEADER_KEY             = "scheduler:leader";
    private static final int    ELECTION_TIMEOUT_SECONDS = 12;
    private static final int    FAILOVER_TIMEOUT_SECONDS = 20;
    private static final int    POLL_INTERVAL_MS         = 500;

    @BeforeEach
    void ensureLeaderKeyCleared() throws InterruptedException {
        // Clear any stale leader key so this test context always starts clean
        // Without this, if main app is running it blocks election
        RBucket<String> bucket = redissonClient.getBucket(LEADER_KEY);
        bucket.delete();
        // Give election loop time to fire and win
        Thread.sleep(6000);
    }

    // ── Test 1 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Node acquires leadership within 12 seconds of startup")
    void nodeShouldAcquireLeadershipOnStartup() {
        Awaitility.await()
                .atMost(ELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(leaderElectionService.isLeader())
                                .as("Node should be leader after election")
                                .isTrue()
                );

        assertThat(leaderElectionService.getLeaderNodeId()).isEqualTo(nodeId);
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /health returns isLeader=true when node is leader")
    void healthEndpointShouldReportLeaderStatus() {
        Awaitility.await()
                .atMost(ELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> leaderElectionService.isLeader());

        @SuppressWarnings("unchecked")
        Map<String, Object> health = restTemplate.getForObject(
                "http://localhost:" + port + "/health", Map.class
        );

        assertThat(health).isNotNull();
        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.get("isLeader")).isEqualTo(true);
        // Don't assert specific nodeId — default differs between test/main contexts
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Node re-elects as leader within 20 seconds after leader key deleted")
    void nodeShouldReElectAfterLeaderKeyDeleted() {
        Awaitility.await()
                .atMost(ELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> leaderElectionService.isLeader());

        assertThat(leaderElectionService.isLeader()).isTrue();

        // Simulate crash — delete leader key
        redissonClient.getBucket(LEADER_KEY).delete();

        // Should re-elect within failover window
        Awaitility.await()
                .atMost(FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(leaderElectionService.isLeader())
                                .as("Node should re-elect after leader key deletion")
                                .isTrue()
                );

        @SuppressWarnings("unchecked")
        Map<String, Object> health = restTemplate.getForObject(
                "http://localhost:" + port + "/health", Map.class
        );
        assertThat(health.get("isLeader")).isEqualTo(true);
        assertThat(health.get("leaderNodeId")).isEqualTo(nodeId);
    }

    // ── Test 4 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Leader key exists in Redis with positive TTL after election")
    void leaderKeyExistsInRedisAfterElection() {
        Awaitility.await()
                .atMost(ELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> leaderElectionService.isLeader());

        RBucket<String> bucket = redissonClient.getBucket(LEADER_KEY);

        Awaitility.await()
                .atMost(ELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    String value = bucket.get();
                    assertThat(value).as("Leader key should exist").isNotNull();
                    assertThat(value).as("Leader key should contain node ID")
                            .contains(nodeId.substring(0, 5));
                });

        Long ttl = bucket.remainTimeToLive();
        assertThat(ttl).as("TTL should be positive").isGreaterThan(0);
    }

    // ── Test 5 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Node re-establishes leadership correctly within 20s after simulated crash")
    void leaderElectionServiceReportsCorrectStateAfterCrash() {
        Awaitility.await()
                .atMost(ELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> leaderElectionService.isLeader());

        // Simulate crash
        redissonClient.getBucket(LEADER_KEY).delete();

        // Re-election completes within failover window
        Awaitility.await()
                .atMost(FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(leaderElectionService.isLeader())
                                .as("Node should be leader again after re-election")
                                .isTrue()
                );

        assertThat(leaderElectionService.getLeaderNodeId())
                .as("Leader node ID should be this node after re-election")
                .isEqualTo(nodeId);
    }
}