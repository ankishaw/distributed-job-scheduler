package com.jobscheduler.leader;

/**
 * Contract for leader election implementations.
 *
 * At any point in time exactly one node across the cluster holds
 * the leader role. Only the leader runs SchedulerLoop.tick() —
 * dispatching jobs, rescheduling cron jobs, and running anti-starvation.
 *
 * Implementations:
 *   RedisLeaderElection  — Redisson RLock with TTL keepalive (default)
 *   EtcdLeaderElection   — jetcd TTL lease + campaign (stronger CP guarantee)
 *
 * Lifecycle:
 *   start() — called on @PostConstruct, begins election loop
 *   stop()  — called on @PreDestroy, releases lock/lease cleanly
 *   isLeader() — called on every scheduler tick (must be fast, no I/O)
 */
public interface LeaderElection {

    /**
     * Start the election process. Non-blocking — runs election in background.
     * The node may not be leader immediately after this returns.
     */
    void start();

    /**
     * Release leadership and stop the election loop cleanly.
     * Called on application shutdown.
     */
    void stop();

    /**
     * Returns true if this node currently holds the leader role.
     * Must be O(1) — reads a local volatile flag, no network call.
     * Called on every scheduler tick (every 2 seconds).
     */
    boolean isLeader();

    /**
     * Returns the node ID of the current leader, or "unknown" if not known.
     * Used for health endpoint and logging only.
     */
    String getLeaderNodeId();
}
