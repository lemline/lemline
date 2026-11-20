-- Use the table name from com.lemline.runner.repositories.SCHEDULE_TABLE
CREATE TABLE IF NOT EXISTS lemline_schedules
(
    id                      TEXT NOT NULL PRIMARY KEY,
    workflow_id             TEXT UNIQUE              NOT NULL,
    workflow_namespace      TEXT             NOT NULL,
    workflow_name           TEXT             NOT NULL,
    workflow_version        TEXT             NOT NULL,
    workflow_position       TEXT                     NOT NULL,
    workflow_state          TEXT                     NOT NULL,
    schedule_after          TEXT,
    schedule_every          TEXT,
    schedule_cron           TEXT,
    schedule_zone           TEXT,
    outbox_scheduled_for    INTEGER,
    outbox_delayed_until    INTEGER,
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
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_workflow_id
    ON lemline_schedules (workflow_id);

-- Create composite index for efficient querying of pending messages
-- Includes outbox_attempt_count for filtering without additional table scans
CREATE INDEX idx_lemline_schedules_processing
    ON lemline_schedules (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_schedules_cleanup
    ON lemline_schedules (cleanup_after);
