package com.jobscheduler.leader;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Spring-managed facade over the LeaderElection implementation.
 *
 * Handles lifecycle (@PostConstruct / @PreDestroy) so the
 * election implementations don't need Spring annotations themselves.
 *
 * Used by:
 *   SchedulerLoop  — if (!leaderElectionService.isLeader()) return;
 *   HealthController — exposes isLeader in /health response
 */
@Service
public class LeaderElectionService {

    private static final Logger log = LoggerFactory.getLogger(LeaderElectionService.class);

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    private final LeaderElection leaderElection;

    public LeaderElectionService(LeaderElection leaderElection) {
        this.leaderElection = leaderElection;
    }

    @PostConstruct
    public void start() {
        leaderElection.start();
        log.info("LeaderElectionService started on node {}", nodeId);
    }

    @PreDestroy
    public void stop() {
        leaderElection.stop();
        log.info("LeaderElectionService stopped on node {}", nodeId);
    }

    /**
     * Fast O(1) check — reads a volatile boolean, no network call.
     * Called on every scheduler tick.
     */
    public boolean isLeader() {
        return leaderElection.isLeader();
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getLeaderNodeId() {
        return leaderElection.getLeaderNodeId();
    }
}
