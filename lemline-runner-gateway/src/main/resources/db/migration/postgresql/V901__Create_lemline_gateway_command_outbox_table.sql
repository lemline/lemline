CREATE TABLE lemline_gateway_command_outbox
(
    id                      uuid PRIMARY KEY,
    workflow_id             uuid         NOT NULL,
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,
    workflow_position       TEXT         NOT NULL,
    workflow_state          TEXT         NOT NULL,
    outbox_scheduled_for    TIMESTAMPTZ(6),
    outbox_delayed_until    TIMESTAMPTZ(6),
    outbox_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_failed_at        TIMESTAMPTZ(6),
    cleanup_after           TIMESTAMPTZ(6),
    created_at              TIMESTAMPTZ(6) NOT NULL,
    updated_at              TIMESTAMPTZ(6)
);

-- Unique constraint on workflow_id (serves as idempotency guard)
CREATE UNIQUE INDEX IF NOT EXISTS idx_lemline_gw_cmd_outbox_workflow_id
    ON lemline_gateway_command_outbox (workflow_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX IF NOT EXISTS idx_lemline_gw_cmd_outbox_processing
    ON lemline_gateway_command_outbox (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Create index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_lemline_gw_cmd_outbox_cleanup
    ON lemline_gateway_command_outbox (cleanup_after)
    WHERE cleanup_after IS NOT NULL;
