-- V1__create_jobs_table.sql

CREATE TABLE jobs (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255)    NOT NULL,
    job_type            VARCHAR(50)     NOT NULL,
    status              VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    payload             JSONB           NOT NULL DEFAULT '{}',
    cron_expression     VARCHAR(100),
    next_run_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    last_run_at         TIMESTAMPTZ,
    priority            INTEGER         NOT NULL DEFAULT 2,
    timeout_seconds     INTEGER         NOT NULL DEFAULT 300,
    max_retries         INTEGER         NOT NULL DEFAULT 3,
    retry_count         INTEGER         NOT NULL DEFAULT 0,
    worker_id           VARCHAR(100),
    error_message       TEXT,
    idempotency_key     VARCHAR(255)    UNIQUE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_dispatch
    ON jobs (priority DESC, created_at ASC)
    WHERE status = 'PENDING';

CREATE INDEX idx_jobs_worker_running
    ON jobs (worker_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_jobs_next_run
    ON jobs (next_run_at ASC)
    WHERE status = 'PENDING' AND cron_expression IS NOT NULL;

CREATE INDEX idx_jobs_payload_gin
    ON jobs USING GIN (payload);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_jobs_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();