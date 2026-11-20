CREATE TABLE IF NOT EXISTS lemline_gateway_command_outbox
(
    id                      TEXT NOT NULL PRIMARY KEY,
    workflow_id             TEXT                     NOT NULL,
    workflow_namespace      TEXT             NOT NULL,
    workflow_name           TEXT             NOT NULL,
    workflow_version        TEXT             NOT NULL,
    workflow_position       TEXT                     NOT NULL,
    workflow_state          TEXT                     NOT NULL,
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

-- Unique constraint on workflow_id (serves as idempotency guard)
CREATE UNIQUE INDEX IF NOT EXISTS idx_lemline_gw_cmd_outbox_workflow_id
    ON lemline_gateway_command_outbox (workflow_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX idx_lemline_gw_cmd_outbox_processing
    ON lemline_gateway_command_outbox (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_gw_cmd_outbox_cleanup
    ON lemline_gateway_command_outbox (cleanup_after);
