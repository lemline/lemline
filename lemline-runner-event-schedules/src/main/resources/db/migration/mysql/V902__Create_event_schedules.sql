-- Use the table name from com.lemline.runner.repositories.EVENT_SCHEDULE_TABLE
CREATE TABLE IF NOT EXISTS lemline_event_schedule_starts
(
    id                      BINARY(16) PRIMARY KEY,
    workflow_id             BINARY(16)   NOT NULL,
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,
    workflow_position       TEXT         NOT NULL,
    workflow_state          MEDIUMTEXT   NOT NULL,
    outbox_scheduled_for    TIMESTAMP(6) NOT NULL,
    outbox_delayed_until    TIMESTAMP(6) NOT NULL,
    outbox_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),
    cleanup_after           TIMESTAMP(6),
    created_at              TIMESTAMP(6) NOT NULL,
    updated_at              TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs;

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_event_schedule_starts_workflow_id
    ON lemline_event_schedule_starts (workflow_id);

-- Create composite index for efficient querying of pending messages
-- Includes outbox_attempt_count for filtering without additional table scans
CREATE INDEX idx_lemline_event_schedule_starts_processing
    ON lemline_event_schedule_starts (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_event_schedule_starts_cleanup
    ON lemline_event_schedule_starts (cleanup_after);

-- One serialized collection state per definition. The definition row is the lock owner.
CREATE TABLE lemline_event_schedule_state (
    id BINARY(16) PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    definition_hash VARCHAR(64) NOT NULL,
    collections LONGTEXT NOT NULL,
    UNIQUE (namespace, name, version),
    FOREIGN KEY (namespace, name, version) REFERENCES lemline_definitions(namespace, name, version) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

-- Keep source+id receipts for the lifetime of the definition, independently of outbox cleanup.
CREATE TABLE lemline_event_schedule_receipts (
    state_id BINARY(16) NOT NULL,
    event_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (state_id, event_key),
    FOREIGN KEY (state_id) REFERENCES lemline_event_schedule_state(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;
