-- Use the table name from com.lemline.runner.repositories.SCHEDULE_TABLE
CREATE TABLE lemline_schedules
(
    id                      BINARY(16) PRIMARY KEY,
    workflow_id             BINARY(16) UNIQUE NOT NULL,
    workflow_namespace      VARCHAR(255)      NOT NULL,
    workflow_name           VARCHAR(255)      NOT NULL,
    workflow_version        VARCHAR(255)      NOT NULL,
    workflow_position       TEXT              NOT NULL,
    workflow_state          MEDIUMTEXT        NOT NULL,
    schedule_after          VARCHAR(255),
    schedule_cron           VARCHAR(255),
    schedule_every          VARCHAR(255),
    schedule_zone           VARCHAR(64),
    outbox_scheduled_for    TIMESTAMP(6),
    outbox_delayed_until    TIMESTAMP(6),
    outbox_attempt_count    INTEGER           NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),
    created_at              TIMESTAMP(6)      NOT NULL,
    updated_at              TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_schedules_workflow_id
    ON lemline_schedules (workflow_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX idx_lemline_schedules_processing
    ON lemline_schedules (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_schedules_completed
    ON lemline_schedules (outbox_completed_at);
