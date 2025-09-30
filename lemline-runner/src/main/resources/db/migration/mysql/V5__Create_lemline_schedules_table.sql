-- Use the table name from com.lemline.runner.repositories.SCHEDULE_TABLE
CREATE TABLE lemline_schedules
(
    id                        BINARY(16) PRIMARY KEY,
    workflow_id               BINARY(16) UNIQUE NOT NULL,
    workflow_namespace        VARCHAR(255)      NOT NULL,
    workflow_name             VARCHAR(255)      NOT NULL,
    workflow_version          VARCHAR(255)      NOT NULL,
    workflow_position         TEXT              NOT NULL,
    workflow_state            MEDIUMTEXT        NOT NULL,
    parent_id                 BINARY(16),
    schedule_after            VARCHAR(255),
    schedule_cron             VARCHAR(255),
    schedule_every            VARCHAR(255),
    schedule_zone             VARCHAR(64),
    run_status                VARCHAR(50)       NOT NULL,
    run_at                    TIMESTAMP(6),
    run_delayed_until         TIMESTAMP(6),
    run_attempt_count         INTEGER           NOT NULL DEFAULT 0,
    run_last_error_class      TEXT,
    run_last_error_message    TEXT,
    run_last_error_stacktrace MEDIUMTEXT,
    created_at                TIMESTAMP(6)      NOT NULL,
    updated_at                TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_schedules_workflow_id
    ON lemline_schedules (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_schedules_parent_id
    ON lemline_schedules (parent_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_schedules_status_delayed_until
    ON lemline_schedules (run_status, run_delayed_until);
