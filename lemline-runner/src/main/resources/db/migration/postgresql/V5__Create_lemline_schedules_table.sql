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
    parent_id               uuid,
    schedule_after          VARCHAR(255),
    schedule_every          VARCHAR(255),
    schedule_cron           VARCHAR(255),
    schedule_zone           VARCHAR(64),
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_scheduled_for    TIMESTAMPTZ(6),
    outbox_delayed_until    TIMESTAMPTZ(6),
    outbox_attempt_count    INTEGER        NOT NULL DEFAULT 0,
    outbox_failed_at        TIMESTAMPTZ(6),
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    created_at              TIMESTAMPTZ(6) NOT NULL,
    updated_at              TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_workflow_id
    ON lemline_schedules (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_parent_id
    ON lemline_schedules (parent_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_processing
    ON lemline_schedules (outbox_completed_at, outbox_failed_at, outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Create index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_completed
    ON lemline_schedules (outbox_completed_at)
    WHERE outbox_completed_at IS NOT NULL;
