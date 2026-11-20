-- Use the table name from com.lemline.runner.repositories.EVENT_SCHEDULE_TABLE
CREATE TABLE IF NOT EXISTS lemline_event_schedule_starts
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
    id TEXT NOT NULL PRIMARY KEY,
    namespace TEXT NOT NULL,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    definition_hash TEXT NOT NULL,
    collections TEXT,
    UNIQUE (namespace, name, version),
    FOREIGN KEY (namespace, name, version) REFERENCES lemline_definitions(namespace, name, version) ON DELETE CASCADE
);

-- Keep source+id receipts for the lifetime of the definition, independently of outbox cleanup.
CREATE TABLE lemline_event_schedule_receipts (
    state_id TEXT NOT NULL,
    event_key TEXT NOT NULL,
    PRIMARY KEY (state_id, event_key),
    FOREIGN KEY (state_id) REFERENCES lemline_event_schedule_state(id) ON DELETE CASCADE
);
