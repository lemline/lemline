-- Use the table name from com.lemline.runner.repositories.WAIT_TABLE
CREATE TABLE IF NOT EXISTS lemline_waits
(
    id                      BINARY(16) PRIMARY KEY,
    workflow_id             BINARY(16)   NOT NULL,
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,
    workflow_position       TEXT         NOT NULL,
    workflow_state          MEDIUMTEXT   NOT NULL,
    parent_id               BINARY(16),
    outbox_scheduled_for    TIMESTAMP(6) NOT NULL,
    outbox_delayed_until    TIMESTAMP(6) NOT NULL,
    outbox_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),
    created_at              TIMESTAMP(6) NOT NULL,
    updated_at              TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_waits_workflow_id
    ON lemline_waits (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_waits_parent_id
    ON lemline_waits (parent_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX idx_lemline_waits_processing
    ON lemline_waits (outbox_completed_at, outbox_failed_at, outbox_delayed_until);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_waits_completed
    ON lemline_waits (outbox_completed_at);
