-- Use the table name from com.lemline.runner.repositories.RETRY_TABLE
CREATE TABLE IF NOT EXISTS lemline_retries
(
    id                      uuid PRIMARY KEY,
    workflow_id             uuid           NOT NULL,
    workflow_namespace      VARCHAR(255)   NOT NULL,
    workflow_name           VARCHAR(255)   NOT NULL,
    workflow_version        VARCHAR(255)   NOT NULL,
    workflow_position       TEXT           NOT NULL,
    workflow_state          TEXT           NOT NULL,
    error_reason            VARCHAR(255)   NOT NULL,
    error_class             TEXT           NOT NULL,
    error_message           TEXT,
    error_stacktrace        TEXT           NOT NULL,
    outbox_scheduled_for    TIMESTAMPTZ(6) NOT NULL,
    outbox_delayed_until    TIMESTAMPTZ(6) NOT NULL,
    outbox_attempt_count    INTEGER        NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_failed_at        TIMESTAMPTZ(6),
    cleanup_after           TIMESTAMPTZ(6),
    created_at              TIMESTAMPTZ(6) NOT NULL,
    updated_at              TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_retries_workflow_id
    ON lemline_retries (workflow_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX IF NOT EXISTS idx_lemline_retries_processing
    ON lemline_retries (outbox_completed_at, outbox_failed_at, outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Create index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_lemline_retries_cleanup
    ON lemline_retries (cleanup_after)
    WHERE cleanup_after IS NOT NULL;
