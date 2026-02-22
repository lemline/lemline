CREATE TABLE IF NOT EXISTS lemline_gateway_command_outbox
(
    id                      UUID PRIMARY KEY,
    workflow_id             UUID                     NOT NULL,
    workflow_namespace      VARCHAR(255)             NOT NULL,
    workflow_name           VARCHAR(255)             NOT NULL,
    workflow_version        VARCHAR(255)             NOT NULL,
    workflow_position       CLOB                     NOT NULL,
    workflow_state          CLOB                     NOT NULL,
    outbox_scheduled_for    TIMESTAMP WITH TIME ZONE,
    outbox_delayed_until    TIMESTAMP WITH TIME ZONE,
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

-- Unique constraint on workflow_id (serves as idempotency guard)
CREATE UNIQUE INDEX IF NOT EXISTS idx_lemline_gw_cmd_outbox_workflow_id
    ON lemline_gateway_command_outbox (workflow_id);

-- Create composite index for efficient querying of pending messages
CREATE INDEX idx_lemline_gw_cmd_outbox_processing
    ON lemline_gateway_command_outbox (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_gw_cmd_outbox_cleanup
    ON lemline_gateway_command_outbox (cleanup_after);
