-- Use the table name from com.lemline.runner.repositories.WAIT_TABLE
CREATE TABLE IF NOT EXISTS lemline_waits
(
    id                      TEXT NOT NULL PRIMARY KEY,
    workflow_id             TEXT                     NOT NULL,
    workflow_namespace      TEXT             NOT NULL,
    workflow_name           TEXT             NOT NULL,
    workflow_version        TEXT             NOT NULL,
    workflow_position       TEXT                     NOT NULL,
    workflow_state          TEXT                     NOT NULL,
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
CREATE INDEX idx_lemline_waits_workflow_id
    ON lemline_waits (workflow_id);

-- Create composite index for efficient querying of pending messages
-- Includes outbox_attempt_count for filtering without additional table scans
CREATE INDEX idx_lemline_waits_processing
    ON lemline_waits (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_waits_cleanup
    ON lemline_waits (cleanup_after);
