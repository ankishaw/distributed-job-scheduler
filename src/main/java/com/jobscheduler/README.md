# Distributed Job Scheduler

A production-grade distributed job scheduler built with Java (Spring Boot), Postgres, and Redis.
Designed to demonstrate SDE-2 level distributed systems competency.

---

## Architecture

```
┌─────────────┐     POST /jobs      ┌──────────────────┐
│   Client    │ ──────────────────► │   API Server      │
│  (Postman)  │                     │  (Spring MVC)     │
└─────────────┘                     └────────┬─────────┘
                                             │ save job (PENDING)
                                             ▼
                                     ┌───────────────┐
                                     │   PostgreSQL   │ ◄── Source of truth
                                     │  jobs table   │
                                     └───────┬───────┘
                                             │
                    ┌────────────────────────┼────────────────────────┐
                    │                        │                        │
                    ▼                        ▼                        ▼
           ┌──────────────┐        ┌──────────────────┐    ┌──────────────────┐
           │ SchedulerLoop │        │   Worker Pool     │    │   Heartbeat &    │
           │  (cron tick) │        │  (5 threads)      │    │   Reclaim Svc    │
           └──────┬───────┘        └────────┬─────────┘    └──────────────────┘
                  │                         │
       reschedule │               SKIP LOCKED claim
       cron jobs  │                         │
                  ▼                         ▼
           ┌──────────────┐        ┌──────────────────┐
           │  Job gets    │        │  JobExecutor     │
           │  PENDING     │        │  WEBHOOK/SHELL   │
           └──────────────┘        └────────┬─────────┘
                                            │
                                   success  │  failure
                                    ┌───────┴──────┐
                                    ▼              ▼
                               COMPLETED       RetryPolicy
                                           (backoff → DEAD_LETTER)
```

---

## Key design decisions

### 1. SELECT FOR UPDATE SKIP LOCKED

Workers claim jobs from Postgres using `SKIP LOCKED`:

```sql
SELECT * FROM jobs
WHERE  status = 'PENDING'
AND    next_run_at <= now()
ORDER  BY priority DESC, created_at ASC
LIMIT  1
FOR UPDATE SKIP LOCKED
```

**Why:** Multiple workers compete for the same rows without any contention.
Worker A locks row 101; Worker B's identical query instantly skips 101 and locks 102.
No blocking, no deadlocks, true parallel execution.

**Why not Kafka?** The claim + status flip happen in one atomic transaction.
With a broker, consumption and the DB write are separate — a crash between them
requires saga patterns or an outbox. SKIP LOCKED handles this for free up to ~50k jobs/min.

### 2. At-least-once delivery with idempotency keys

A worker can claim, execute, then crash before ACKing.
The heartbeat reclaimer re-delivers the job — it runs again.
This is at-least-once delivery. To prevent double-effects (e.g. double-charging):

- Each job carries an optional `idempotency_key`
- The handler checks for a completed run with that key before executing
- If found: short-circuit. If not: execute + record in one transaction.

Result: exactly-once *effect* with at-least-once *delivery*.

### 3. Fault detection via heartbeats

Workers write `last_seen = now()` every 10 seconds.
The `StaleJobReclaimer` runs every 30 seconds:

```
if worker.last_seen < now() - 30s:
    → reclaim all RUNNING jobs owned by that worker back to PENDING
    → delete the dead worker row
```

No manual intervention. System heals itself within one reclaimer cycle.
JVM GC pauses are the silent killer — tune G1GC and set thresholds generously.

### 4. Retry with exponential backoff

Failed jobs retry with `2^retryCount` second backoff (2s, 4s, 8s, 16s...).
After `maxRetries` exhausted → `DEAD_LETTER`. Every attempt writes a `job_runs` row.
Dead-lettered jobs require human or alerting intervention — never silently discarded.

### 5. Cron scheduling

Recurring jobs carry a `cronExpression` (Spring 6-field format: `second minute hour DOM month DOW`).
After each completion, `SchedulerLoop` detects `status=COMPLETED + cronExpression != null`
and computes the next `nextRunAt` using `CronExpression.parse().next()`.
The job resets to PENDING and the cycle repeats indefinitely.

### 6. Priority dispatch with anti-starvation

Jobs are dispatched `ORDER BY priority DESC, created_at ASC`.
CRITICAL jobs always go before LOW jobs at the same tick.

**Starvation fix:** A `@Scheduled` query runs every scheduler tick and bumps
priority by 1 for any PENDING job waiting more than 5 minutes.
LOW jobs can never wait indefinitely.

---

## Job state machine

```
              ┌──────────────────────┐
              │                      │
   submit ──► PENDING ──► RUNNING ──► COMPLETED (terminal)
              │             │
              │    crash    │ error / timeout
              │ ◄───────────┘
              │   (reclaimed)
              │             │
              │  retry+backoff       ▼
              └──────────── FAILED ──► DEAD_LETTER (terminal)
              │
              ▼
           CANCELLED (terminal) ◄── DELETE /jobs/:id
```

---

## Tech stack

| Layer | Technology | Why |
|---|---|---|
| API | Spring Boot 3.2 / Spring MVC | Production standard, @Valid, ProblemDetail |
| DB | PostgreSQL 16 | SKIP LOCKED, jsonb, partial indexes |
| Queue | Redis (Lettuce) | Fast in-memory queue for hot dispatch path |
| Coordination | etcd / Redisson | TTL lease leader election |
| Migrations | Flyway | Versioned, repeatable schema management |
| Metrics | Micrometer → Prometheus | Counters, gauges, histograms |
| Dashboards | Grafana | Real-time throughput and latency |
| Tests | JUnit 5 + Mockito + Awaitility | Unit + integration |

---

## Project structure

```
src/main/java/com/jobscheduler/
├── api/
│   ├── controller/    JobController, HealthController
│   ├── dto/           CreateJobRequest, JobResponse, ErrorResponse
│   └── exception/     GlobalExceptionHandler, JobNotFoundException
├── domain/
│   ├── model/         Job, JobRun, Worker, enums
│   └── repository/    JobRepository (SKIP LOCKED), JobRunRepository, WorkerRepository
├── scheduler/         SchedulerLoop, CronExpressionEvaluator
├── worker/
│   ├── handler/       JobHandler interface, WebhookJobHandler, ShellJobHandler
│   ├── WorkerPool     Thread pool + SKIP LOCKED claiming
│   ├── JobExecutor    Execution engine + timeout + metrics
│   └── HeartbeatAndReclaimService
├── service/           JobService, RetryPolicyService
├── metrics/           JobMetrics (Prometheus)
└── config/            AppProperties, WorkerConfig

src/main/resources/
├── application.yml              Base config
├── application-dev.yml          Local dev overrides
└── db/migration/
    ├── V1__create_jobs_table.sql
    ├── V2__create_job_runs_table.sql
    └── V3__create_workers_table.sql
```

---

## Running locally

**Prerequisites:** Java 21, Docker Desktop, Maven 3.9+

```bash
# 1. Start infrastructure
docker compose up postgres redis -d

# 2. Run the app (dev profile — single node, no etcd needed)
SPRING_PROFILES_ACTIVE=dev,scheduler,worker NODE_ID=local-1 mvn spring-boot:run

# 3. Submit a job
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"name":"hello","jobType":"SHELL","payload":{"command":"echo hello world"}}'

# 4. Check status (use id from response above)
curl http://localhost:8080/api/v1/jobs/{id}

# 5. View metrics
curl http://localhost:8080/actuator/prometheus | grep jobs_
```

---

## API reference

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/jobs` | Submit a new job |
| `GET` | `/api/v1/jobs/{id}` | Get job status |
| `GET` | `/api/v1/jobs?status=PENDING` | List jobs with filter |
| `DELETE` | `/api/v1/jobs/{id}` | Cancel a pending job |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

### Job payload shapes

```json
// SHELL job
{
  "name": "daily-cleanup",
  "jobType": "SHELL",
  "payload": { "command": "echo hello" },
  "cronExpression": "0 0 2 * * *",
  "priority": "MEDIUM",
  "maxRetries": 3,
  "timeoutSeconds": 300
}

// WEBHOOK job
{
  "name": "payment-notify",
  "jobType": "WEBHOOK",
  "payload": {
    "url": "https://api.example.com/notify",
    "httpMethod": "POST",
    "body": { "event": "payment.completed" }
  },
  "priority": "CRITICAL",
  "maxRetries": 5,
  "idempotencyKey": "payment-991-notify"
}
```

---

## Key metrics

| Metric | Type | Description |
|---|---|---|
| `jobs_submitted_total` | Counter | Jobs created, tagged by type+priority |
| `jobs_completed_total` | Counter | Successful executions, tagged by type |
| `jobs_failed_total` | Counter | Failed executions, tagged by type+reason |
| `jobs_dead_letter_total` | Counter | Jobs that exhausted retries |
| `jobs_pending_count` | Gauge | Current pending queue depth |
| `jobs_running_count` | Gauge | Currently executing jobs |
| `job_execution_duration_seconds` | Histogram | p50/p95/p99 execution latency |

**Grafana queries:**
```
Throughput:  rate(jobs_completed_total[1m]) * 60
Error rate:  rate(jobs_failed_total[5m]) / rate(jobs_submitted_total[5m]) * 100
p99 latency: histogram_quantile(0.99, rate(job_execution_duration_seconds_bucket[5m]))
```

---

## Scaling beyond this implementation

| Bottleneck | Solution |
|---|---|
| >50k jobs/min | Replace SKIP LOCKED with Redis queue (BRPOPLPUSH) |
| Single Postgres | Shard jobs table by tenant_id |
| Single scheduler | Leader election via etcd (Phase 2) |
| One job type | Add JobHandler @Component + JobType enum value |
| Windows commands | ShellJobHandler auto-detects OS (cmd.exe vs sh) |

---

## Trade-offs documented

**SKIP LOCKED vs Kafka:** SKIP LOCKED wins below 50k jobs/min — one system,
atomic claim, no saga patterns needed. Kafka wins at high throughput or when
replay/fan-out is required.

**etcd vs Redisson for leader election:** etcd provides stronger consistency
under network partitions (CP). Redisson is simpler but can split-brain under
partition (AP). etcd is the correct choice for a scheduler.

**At-least-once vs exactly-once:** True exactly-once requires 2PC — too expensive.
At-least-once + idempotency keys gives exactly-once effects at the cost of
one extra DB read per execution. Always the right trade-off for this use case.
