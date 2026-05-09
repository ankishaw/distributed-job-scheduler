package com.jobscheduler.worker;

import com.jobscheduler.config.AppProperties;
import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.model.Worker;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.domain.repository.WorkerRepository;
import com.jobscheduler.queue.RedisJobQueue;
import com.jobscheduler.worker.handler.JobHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Worker pool with two-layer job dispatch:
 *
 * Layer 1 — Redis BRPOPLPUSH (hot path):
 *   Workers block on Redis waiting for job IDs.
 *   Sub-millisecond dispatch latency.
 *   Atomic move to jobs:processing prevents loss on worker crash.
 *
 * Layer 2 — Postgres SKIP LOCKED (fallback):
 *   If Redis returns nothing (timeout or unavailable),
 *   workers fall back to polling Postgres directly.
 *   Zero job loss even if Redis goes down entirely.
 *
 * This matches the Sidekiq production pattern:
 *   "Redis for speed, Postgres for durability"
 */
@Component
public class WorkerPool {

    private final JobClaimService jobClaimService;

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    // How long workers block on Redis before falling back to SKIP LOCKED
    private static final int REDIS_CLAIM_TIMEOUT_SECONDS = 1;

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    private final AppProperties     appProperties;
    private final JobRepository     jobRepository;
    private final WorkerRepository  workerRepository;
    private final JobExecutor       jobExecutor;
    private final RedisJobQueue     redisJobQueue;
    private final Map<String, JobHandler> handlerMap;

    private ExecutorService executorService;
    private volatile boolean running = false;

    public WorkerPool(AppProperties appProperties,
                      JobRepository jobRepository,
                      WorkerRepository workerRepository,
                      JobExecutor jobExecutor,
                      RedisJobQueue redisJobQueue,
                      JobClaimService jobClaimService,
                      List<JobHandler> handlers) {
        this.appProperties  = appProperties;
        this.jobRepository  = jobRepository;
        this.workerRepository = workerRepository;
        this.jobExecutor    = jobExecutor;
        this.redisJobQueue  = redisJobQueue;
        this.jobClaimService = jobClaimService;
        this.handlerMap     = handlers.stream()
            .collect(Collectors.toMap(h -> h.getJobType().name(), Function.identity()));
        log.info("Registered {} job handlers: {}", handlerMap.size(), handlerMap.keySet());
    }

    @PostConstruct
    public void start() {
        int concurrency = appProperties.getWorker().getConcurrency();
        running = true;

        registerWorker();

        executorService = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r);
            t.setName("worker-" + t.getId());
            t.setDaemon(false);
            return t;
        });

        for (int i = 0; i < concurrency; i++) {
            executorService.submit(this::workerLoop);
        }

        log.info("WorkerPool started with {} threads on node {}", concurrency, nodeId);
    }

    @PreDestroy
    public void stop() {
        log.info("WorkerPool shutting down — waiting up to 30s for in-flight jobs");
        running = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Worker loop ───────────────────────────────────────────────────────────

    private void workerLoop() {
        while (running) {
            try {
                UUID jobId = jobClaimService.claimNextJob();  // ← CHANGE THIS
                if (jobId != null) {
                    jobExecutor.execute(jobId, nodeId);
                    redisJobQueue.complete(jobId);
                }
            } catch (Exception e) {
                log.error("Unexpected error in worker loop", e);
                try { Thread.sleep(2000); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ── Two-layer claim ───────────────────────────────────────────────────────

    /**
     * Claim a job using two-layer strategy:
     *
     * 1. Try Redis BRPOPLPUSH first (blocks for REDIS_CLAIM_TIMEOUT_SECONDS)
     *    → fast dispatch, atomic move to processing queue
     *
     * 2. If Redis returns nothing → fall back to Postgres SKIP LOCKED
     *    → slower but guaranteed — works even when Redis is down
     *
     * Returns job ID if claimed, null if no jobs available.
     */


    /**
     * Atomically mark a job as RUNNING in Postgres.
     * Uses an UPDATE WHERE status='PENDING' to prevent double-claiming.
     * Returns number of rows updated (1 = success, 0 = already claimed).
     */


    // ── Worker registration ───────────────────────────────────────────────────

    private void registerWorker() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            Worker worker   = new Worker(nodeId, hostname);
            workerRepository.save(worker);
            log.info("Worker registered: nodeId={}, hostname={}", nodeId, hostname);
        } catch (Exception e) {
            log.warn("Could not register worker (non-fatal): {}", e.getMessage());
        }
    }

    public Map<String, JobHandler> getHandlerMap() { return handlerMap; }
}
