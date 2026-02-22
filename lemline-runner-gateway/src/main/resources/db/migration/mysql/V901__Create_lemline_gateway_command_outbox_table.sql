CREATE TABLE lemline_gateway_command_outbox
(
    id                      BINARY(16) PRIMARY KEY,
    workflow_id             BINARY(16)   NOT NULL,
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,
    workflow_position       TEXT         NOT NULL,
    workflow_state          MEDIUMTEXT   NOT NULL,
    outbox_scheduled_for    TIMESTAMP(6),
    outbox_delayed_until    TIMESTAMP(6),
    outbox_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace MEDIUMTEXT,
    outbox_completed_at     TIMESTAMP(6),
    outbox_failed_at        TIMESTAMP(6),
    cleanup_after           TIMESTAMP(6),
    created_at              TIMESTAMP(6) NOT NULL,
    updated_at              TIMESTAMP(6),
    UNIQUE INDEX idx_lemline_gw_cmd_outbox_workflow_id (workflow_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Create composite index for efficient querying of pending messages
CREATE INDEX idx_lemline_gw_cmd_outbox_processing
    ON lemline_gateway_command_outbox (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_gw_cmd_outbox_cleanup
    ON lemline_gateway_command_outbox (cleanup_after);
