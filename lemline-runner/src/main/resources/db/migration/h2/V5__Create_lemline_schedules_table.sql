-- Use the table name from com.lemline.runner.repositories.SCHEDULE_TABLE
CREATE TABLE IF NOT EXISTS lemline_schedules
(
    id                      UUID PRIMARY KEY,
    workflow_id             UUID UNIQUE              NOT NULL,
    workflow_namespace      VARCHAR(255)             NOT NULL,
    workflow_name           VARCHAR(255)             NOT NULL,
    workflow_version        VARCHAR(255)             NOT NULL,
    workflow_position       CLOB                     NOT NULL,
    workflow_state          CLOB                     NOT NULL,
    parent_id               UUID,
    schedule_after          VARCHAR(255),
    schedule_every          VARCHAR(255),
    schedule_cron           VARCHAR(255),
    schedule_zone           VARCHAR(64),
    outbox_scheduled_for    TIMESTAMP WITH TIME ZONE,
    outbox_delayed_until    TIMESTAMP WITH TIME ZONE,
    outbox_attempt_count    INTEGER                  NOT NULL DEFAULT 0,
    outbox_error_class      CLOB,
    outbox_error_message    CLOB,
    outbox_error_stacktrace CLOB,
    outbox_completed_at     TIMESTAMP WITH TIME ZONE,
    outbox_failed_at        TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_schedules_workflow_id
    ON lemline_schedules (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_schedules_parent_id
    ON lemline_schedules (parent_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX idx_lemline_schedules_processing
    ON lemline_schedules (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_schedules_completed
    ON lemline_schedules (outbox_completed_at);
