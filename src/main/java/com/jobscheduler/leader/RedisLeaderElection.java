package com.jobscheduler.leader;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leader election via Redis SETNX pattern using Redisson RBucket.
 *
 * How it works:
 *   1. Every node tries setIfAbsent("scheduler:leader", nodeId, TTL=30s)
 *      This is equivalent to Redis SETNX — atomic, exactly one winner.
 *   2. The winner sets isLeader=true.
 *   3. A keepalive thread reads the key every 10s:
 *      - If value == our nodeId → renew TTL (we're still leader)
 *      - If value != our nodeId → step down (we lost the lock)
 *      - If key missing         → step down (TTL expired)
 *   4. Non-leaders retry setIfAbsent every 5s.
 *
 * Why RBucket over RLock:
 *   RLock with watchdog is thread-affine — the lock is tied to the thread
 *   that acquired it. When that thread returns to the pool, the watchdog
 *   releases the lock automatically. RBucket has no thread affinity.
 *
 * Failover time:
 *   Best case:  5s (retry interval) if leader releases cleanly on shutdown.
 *   Worst case: 30s (TTL) if leader crashes without releasing.
 */
@Component
@Primary
public class RedisLeaderElection implements LeaderElection {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaderElection.class);

    private static final String   LEADER_KEY  = "scheduler:leader";
    private static final Duration LOCK_TTL    = Duration.ofSeconds(30);
    private static final Duration KEEPALIVE   = Duration.ofSeconds(10);
    private static final long     RETRY_MS    = 5_000L;

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    private final RedissonClient      redisson;
    private final AtomicBoolean       leader   = new AtomicBoolean(false);
    private       RBucket<String>     bucket;
    private       ScheduledExecutorService keepaliveExecutor;
    private       ScheduledExecutorService electionExecutor;
    private volatile boolean          running  = false;

    public RedisLeaderElection(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public void start() {
        running = true;
        bucket  = redisson.getBucket(LEADER_KEY);
        log.info("RedisLeaderElection.start() called for node {} — bucket key: {}", nodeId, LEADER_KEY);

        keepaliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepaliveExecutor.scheduleWithFixedDelay(
                this::renewLock,
                KEEPALIVE.getSeconds(), KEEPALIVE.getSeconds(), TimeUnit.SECONDS
        );

        electionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-election");
            t.setDaemon(true);
            return t;
        });
        electionExecutor.scheduleWithFixedDelay(
                this::tryAcquire, 0, RETRY_MS, TimeUnit.MILLISECONDS
        );

        log.info("RedisLeaderElection started for node {} (TTL={}s, retry={}ms)",
                nodeId, LOCK_TTL.getSeconds(), RETRY_MS);
    }

    @Override
    public void stop() {
        running = false;
        if (leader.getAndSet(false)) {
            try {
                String current = bucket.get();
                if (nodeId.equals(current)) {
                    bucket.delete();
                    log.info("Node {} released leader key on shutdown — fast failover triggered", nodeId);
                }
            } catch (Exception e) {
                log.warn("Could not release leader key on shutdown: {}", e.getMessage());
            }
        }
        if (keepaliveExecutor != null) keepaliveExecutor.shutdownNow();
        if (electionExecutor  != null) electionExecutor.shutdownNow();
    }

    @Override
    public boolean isLeader() {
        return leader.get();
    }

//    @Override
//    public String getLeaderNodeId() {
//        try {
//            String current = bucket.get();
//            return current != null ? current : "none";
//        } catch (Exception e) {
//            return "unknown";
//        }
//    }
    @Override
    public String getLeaderNodeId() {
        // If we ARE the leader, we know our own nodeId — no Redis read needed
        // Redis read has codec issues and isn't needed for this simple case
        if (leader.get()) return nodeId;
        // If we're not leader, we don't know who is — return unknown
        return "unknown";
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Try to become leader via SETNX.
     * Only runs when we are NOT already the leader.
     */
    private void tryAcquire() {
        if (!running || leader.get()) return;
        try {
            log.info("Node {} attempting to acquire leadership...", nodeId);
            boolean won = bucket.setIfAbsent(nodeId, LOCK_TTL);
            log.info("Node {} setIfAbsent result: {}", nodeId, won);
            if (won) {
                leader.set(true);
                log.info("Node {} elected as LEADER (TTL={}s)", nodeId, LOCK_TTL.getSeconds());
            }
        } catch (Exception e) {
            log.error("Leader election FAILED for node {}: {}", nodeId, e.getMessage(), e);
        }
    }

    /**
     * Renew our TTL if we are still the recorded leader.
     * If we lost the key (Redis restart, partition), step down.
     */
    private void renewLock() {
        if (!running || !leader.get()) return;
        try {
            String current = bucket.get();
            log.info("Node {} keepalive check — Redis value: '{}'", nodeId, current); // ADD THIS
            if (nodeId.equals(current)) {
                bucket.set(nodeId, LOCK_TTL);
                log.debug("Node {} renewed leader TTL ({}s)", nodeId, LOCK_TTL.getSeconds());
            } else {
                leader.set(false);
                log.warn("Node {} lost leadership (current leader: {}) — stepping down",
                        nodeId, current != null ? current : "none");
            }
        } catch (Exception e) {
            leader.set(false);
            log.error("Node {} keepalive failed — stepping down: {}", nodeId, e.getMessage());
        }
    }
}