-- Use the table name from com.lemline.runner.repositories.RETRY_TABLE
CREATE TABLE IF NOT EXISTS lemline_retries
(
    id                      TEXT NOT NULL PRIMARY KEY,
    workflow_id             TEXT                     NOT NULL,
    workflow_namespace      TEXT             NOT NULL,
    workflow_name           TEXT             NOT NULL,
    workflow_version        TEXT             NOT NULL,
    workflow_position       TEXT                     NOT NULL,
    workflow_state          TEXT                     NOT NULL,
    error_reason            TEXT             NOT NULL,
    error_class             TEXT                     NOT NULL,
    error_message           TEXT,
    error_stacktrace        TEXT,
    outbox_scheduled_for    INTEGER NOT NULL,
    outbox_delayed_until    INTEGER NOT NULL,
    outbox_attempt_count    INTEGER                  NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     INTEGER,
    outbox_failed_at        INTEGER,
    cleanup_after           INTEGER,
    created_at              INTEGER NOT NULL,
    updated_at              INTEGER
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_retries_workflow_id
    ON lemline_retries (workflow_id);

-- Create composite index for efficient querying of pending messages
-- Includes outbox_attempt_count for filtering without additional table scans
CREATE INDEX idx_lemline_retries_processing
    ON lemline_retries (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_retries_cleanup
    ON lemline_retries (cleanup_after);
