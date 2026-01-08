-- Use the table name from com.lemline.runner.repositories.FAILURES_TABLE
CREATE TABLE IF NOT EXISTS lemline_failures
(
    id                 UUID PRIMARY KEY,
    workflow_id        UUID,
    workflow_namespace VARCHAR(255),
    workflow_name      VARCHAR(255),
    workflow_version   VARCHAR(255),
    workflow_position  TEXT,
    workflow_state     TEXT,
    payload            TEXT,
    error_reason       VARCHAR(255)   NOT NULL,
    error_class        TEXT           NOT NULL,
    error_message      TEXT,
    error_stacktrace   TEXT           NOT NULL,
    created_at         TIMESTAMPTZ(6) NOT NULL,
    updated_at         TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_failures_workflow_id
    ON lemline_failures (workflow_id);

-- Index for error analysis queries (by reason, ordered by time)
CREATE INDEX IF NOT EXISTS idx_lemline_failures_reason_time
    ON lemline_failures (error_reason, created_at DESC);

-- Index for workflow definition-level debugging
CREATE INDEX IF NOT EXISTS idx_lemline_failures_workflow_def
    ON lemline_failures (workflow_namespace, workflow_name, created_at DESC);

