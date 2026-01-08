-- Use the table name from com.lemline.runner.repositories.SCHEDULE_TABLE
CREATE TABLE lemline_schedules
(
    id                      uuid PRIMARY KEY,
    workflow_id             uuid UNIQUE    NOT NULL,
    workflow_namespace      VARCHAR(255)   NOT NULL,
    workflow_name           VARCHAR(255)   NOT NULL,
    workflow_version        VARCHAR(255)   NOT NULL,
    workflow_position       TEXT           NOT NULL,
    workflow_state          TEXT           NOT NULL,
    schedule_after          VARCHAR(255),
    schedule_every          VARCHAR(255),
    schedule_cron           VARCHAR(255),
    schedule_zone           VARCHAR(64),
    outbox_scheduled_for    TIMESTAMPTZ(6),
    outbox_delayed_until    TIMESTAMPTZ(6),
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
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_workflow_id
    ON lemline_schedules (workflow_id);

-- Create composite index for efficient querying of pending messages
-- Includes outbox_attempt_count for filtering without additional table scans
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_processing
    ON lemline_schedules (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Create index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_cleanup
    ON lemline_schedules (cleanup_after)
    WHERE cleanup_after IS NOT NULL;
