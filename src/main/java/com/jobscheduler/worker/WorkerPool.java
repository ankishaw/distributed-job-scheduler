package com.jobscheduler.worker;

import com.jobscheduler.config.AppProperties;
import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobStatus;
import com.jobscheduler.domain.model.Worker;
import com.jobscheduler.domain.repository.JobRepository;
import com.jobscheduler.domain.repository.WorkerRepository;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manages the worker thread pool.
 *
 * On startup:
 *   1. Registers this node in the workers table
 *   2. Builds Map<JobType, JobHandler> from all @Component handlers
 *   3. Spawns N worker threads (N = scheduler.worker.concurrency)
 *
 * Each worker thread loops:
 *   - Claim a PENDING job via SELECT FOR UPDATE SKIP LOCKED
 *   - Mark it RUNNING in the same transaction
 *   - Hand it to JobExecutor for actual execution
 *   - Sleep briefly if no jobs are available
 *
 * On shutdown: waits up to 30s for in-flight jobs to complete.
 */
@Component
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    private final AppProperties appProperties;
    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;
    private final JobExecutor jobExecutor;
    private final Map<String, JobHandler> handlerMap;

    private ExecutorService executorService;
    private volatile boolean running = false;

    public WorkerPool(AppProperties appProperties,
                      JobRepository jobRepository,
                      WorkerRepository workerRepository,
                      JobExecutor jobExecutor,
                      List<JobHandler> handlers) {
        this.appProperties   = appProperties;
        this.jobRepository   = jobRepository;
        this.workerRepository = workerRepository;
        this.jobExecutor     = jobExecutor;
        // Build handler registry: JobType name → handler
        this.handlerMap = handlers.stream()
            .collect(Collectors.toMap(
                h -> h.getJobType().name(),
                Function.identity()
            ));
        log.info("Registered {} job handlers: {}", handlerMap.size(), handlerMap.keySet());
    }

    @PostConstruct
    public void start() {
        int concurrency = appProperties.getWorker().getConcurrency();
        running = true;

        // Register this worker node in the DB
        registerWorker();

        // Spawn worker threads
        executorService = Executors.newFixedThreadPool(
            concurrency,
            r -> {
                Thread t = new Thread(r);
                t.setName("worker-" + t.getId());
                t.setDaemon(false);
                return t;
            }
        );

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
                log.warn("WorkerPool forced shutdown after 30s timeout");
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("WorkerPool stopped");
    }

    // ── Worker loop ───────────────────────────────────────────────────────────

    private void workerLoop() {
        while (running) {
            try {
                UUID jobId = claimNextJob();
                if (jobId != null) {
                    jobExecutor.execute(jobId, nodeId);
                } else {
                    // No jobs available — back off briefly to avoid busy-waiting
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in worker loop", e);
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Claim one PENDING job atomically using SKIP LOCKED.
     * Returns the job ID if claimed, null if no jobs are available.
     *
     * The claim (PENDING → RUNNING) and the workerId assignment happen
     * in the same @Transactional method — no gap where another worker
     * can claim the same job.
     */
    @Transactional
    protected UUID claimNextJob() {
        List<Job> jobs = jobRepository.claimPendingJobs(Instant.now(), 1);
        if (jobs.isEmpty()) {
            return null;
        }
        Job job = jobs.get(0);
        job.setStatus(JobStatus.RUNNING);
        job.setWorkerId(nodeId);
        jobRepository.save(job);
        log.debug("Claimed job {} (type={})", job.getId(), job.getJobType());
        return job.getId();
    }

    // ── Worker registration ───────────────────────────────────────────────────

    private void registerWorker() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            Worker worker = new Worker(nodeId, hostname);
            workerRepository.save(worker);
            log.info("Worker registered: nodeId={}, hostname={}", nodeId, hostname);
        } catch (Exception e) {
            log.warn("Could not register worker (non-fatal): {}", e.getMessage());
        }
    }

    public Map<String, JobHandler> getHandlerMap() {
        return handlerMap;
    }
}
