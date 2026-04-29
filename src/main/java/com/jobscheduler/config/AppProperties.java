package com.jobscheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * Centralised configuration — all values in application.yml under "scheduler.*".
 *
 * Example application.yml:
 *
 *   scheduler:
 *     poll-interval-ms: 5000
 *     dispatch-batch-size: 50
 *     stale-worker-threshold-seconds: 30
 *     heartbeat-interval-ms: 10000
 *     worker:
 *       concurrency: 5
 *     leader:
 *       backend: etcd        # or redis-lock
 *       lease-ttl-seconds: 15
 */
@Component
@ConfigurationProperties(prefix = "scheduler")
public class AppProperties {

    /** How often the SchedulerLoop polls Postgres for due jobs (ms). */
    private long pollIntervalMs = 5_000;

    /** Max jobs dispatched per scheduler tick — prevents Redis queue flooding. */
    private int dispatchBatchSize = 50;

    /**
     * Seconds after which a worker with no heartbeat is declared dead.
     * Must be >= heartbeatIntervalMs * 3.
     */
    private int staleWorkerThresholdSeconds = 30;

    /** How often workers update their last_seen timestamp (ms). */
    private long heartbeatIntervalMs = 10_000;

    private Worker worker = new Worker();
    private Leader leader = new Leader();

    // ── Nested: Worker ──────────────────────────────────────────────────────

    public static class Worker {
        /** Thread pool size per worker instance. Tune to your hardware. */
        private int concurrency = 5;

        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    }

    // ── Nested: Leader ──────────────────────────────────────────────────────

    public static class Leader {
        /**
         * Leader election backend.
         *   "etcd"       → EtcdLeaderElection (preferred, stronger consistency)
         *   "redis-lock" → RedisLeaderElection (simpler, Redlock)
         */
        private String backend = "etcd";

        /** etcd lease TTL in seconds. Renewal happens every TTL/3 seconds. */
        private int leaseTtlSeconds = 15;

        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }

        public int getLeaseTtlSeconds() { return leaseTtlSeconds; }
        public void setLeaseTtlSeconds(int leaseTtlSeconds) { this.leaseTtlSeconds = leaseTtlSeconds; }
    }

    // ── Getters & Setters ───────────────────────────────────────────────────

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getDispatchBatchSize() { return dispatchBatchSize; }
    public void setDispatchBatchSize(int dispatchBatchSize) { this.dispatchBatchSize = dispatchBatchSize; }

    public int getStaleWorkerThresholdSeconds() { return staleWorkerThresholdSeconds; }
    public void setStaleWorkerThresholdSeconds(int v) { this.staleWorkerThresholdSeconds = v; }

    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }

    public Worker getWorker() { return worker; }
    public void setWorker(Worker worker) { this.worker = worker; }

    public Leader getLeader() { return leader; }
    public void setLeader(Leader leader) { this.leader = leader; }
}
