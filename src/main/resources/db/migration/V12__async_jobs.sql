-- V15__async_jobs.sql
-- Persistent async job tracking for long-running operations.
-- Uses UUID primary key (gen_random_uuid()) so IDs are safe to expose publicly.

CREATE TYPE job_status AS ENUM ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED');

CREATE TABLE async_job (
                           id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                           school_id      BIGINT       NOT NULL REFERENCES school(id),
                           job_type       VARCHAR(50)  NOT NULL,          -- e.g. BULK_REPORT_CARD
                           status         job_status   NOT NULL DEFAULT 'QUEUED',
                           requested_by   BIGINT       REFERENCES app_user(id),
                           params         JSONB,                           -- input: termId, classSessionId, etc.
                           result_summary JSONB,                           -- output: { generated, failed }
                           error_message  TEXT,
                           progress_pct   INT          NOT NULL DEFAULT 0,
                           created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                           started_at     TIMESTAMPTZ,
                           completed_at   TIMESTAMPTZ
);

CREATE INDEX idx_async_job_school_type   ON async_job(school_id, job_type);
CREATE INDEX idx_async_job_school_status ON async_job(school_id, status);
CREATE INDEX idx_async_job_created       ON async_job(created_at DESC);

-- Each generated file produced by a job.
-- content is the raw PDF bytes stored inline.
-- For large deployments, replace content with an S3 key; the rest of the model stays identical.
CREATE TABLE async_job_file (
                                id         BIGSERIAL    PRIMARY KEY,
                                job_id     UUID         NOT NULL REFERENCES async_job(id) ON DELETE CASCADE,
                                filename   VARCHAR(255) NOT NULL,
                                content    BYTEA        NOT NULL,
                                created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_async_job_file_job ON async_job_file(job_id);
