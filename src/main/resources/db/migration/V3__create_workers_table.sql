-- V3__create_workers_table.sql
-- ─────────────────────────────────────────────────────────────────────────────
-- Worker registry. PK is NODE_ID (string from env var), not UUID.
-- HeartbeatService upserts this row every 10 seconds.
-- StaleJobReclaimer queries WHERE last_seen < now() - 30s.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE workers (
    node_id         VARCHAR(100)    PRIMARY KEY,
    hostname        VARCHAR(255)    NOT NULL,
    last_seen       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Reclaim query uses this: find dead workers fast, even with many worker rows
CREATE INDEX idx_workers_last_seen ON workers (last_seen);
