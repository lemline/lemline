-- Use the table name from com.lemline.runner.repositories.EVENT_SCHEDULE_TABLE
CREATE TABLE IF NOT EXISTS lemline_event_schedule_starts
(
    id                      UUID PRIMARY KEY,
    workflow_id             UUID                     NOT NULL,
    workflow_namespace      VARCHAR(255)             NOT NULL,
    workflow_name           VARCHAR(255)             NOT NULL,
    workflow_version        VARCHAR(255)             NOT NULL,
    workflow_position       CLOB                     NOT NULL,
    workflow_state          CLOB                     NOT NULL,
    outbox_scheduled_for    TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_delayed_until    TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_attempt_count    INTEGER                  NOT NULL DEFAULT 0,
    outbox_error_class      CLOB,
    outbox_error_message    CLOB,
    outbox_error_stacktrace CLOB,
    outbox_completed_at     TIMESTAMP WITH TIME ZONE,
    outbox_failed_at        TIMESTAMP WITH TIME ZONE,
    cleanup_after           TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE
);

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
    id UUID PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    definition_hash VARCHAR(64) NOT NULL,
    collections CLOB NOT NULL,
    UNIQUE (namespace, name, version),
    FOREIGN KEY (namespace, name, version) REFERENCES lemline_definitions(namespace, name, version) ON DELETE CASCADE
);

-- Keep source+id receipts for the lifetime of the definition, independently of outbox cleanup.
CREATE TABLE lemline_event_schedule_receipts (
    state_id UUID NOT NULL,
    event_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (state_id, event_key),
    FOREIGN KEY (state_id) REFERENCES lemline_event_schedule_state(id) ON DELETE CASCADE
);
