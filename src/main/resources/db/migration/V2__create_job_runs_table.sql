-- V2__create_job_runs_table.sql

CREATE TABLE job_runs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    worker_id       VARCHAR(100),
    status          VARCHAR(50) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    attempt_number  INTEGER     NOT NULL DEFAULT 1,
    error_message   TEXT,
    output          TEXT
);

CREATE INDEX idx_job_runs_job_id
    ON job_runs (job_id, started_at DESC);

CREATE INDEX idx_job_runs_worker_status
    ON job_runs (worker_id, status);