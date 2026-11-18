-- Lifecycle events that must survive a crash between the accepting commit and their publication:
-- workflow.created and the initial /do task.created (written with the initial command) and fork
-- branch task.created (written with the fork). The primary key is the deterministic CloudEvent ID.
CREATE TABLE lemline_creation_event_outbox
(
    id                      BINARY(16) PRIMARY KEY,
    workflow_id             BINARY(16)   NOT NULL,
    workflow_namespace      VARCHAR(255) NOT NULL,
    event_type              VARCHAR(255) NOT NULL,
    event_time              TIMESTAMP(6) NOT NULL,
    event_payload           MEDIUMTEXT   NOT NULL,
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
    updated_at              TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_lemline_creation_event_outbox_processing
    ON lemline_creation_event_outbox (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

CREATE INDEX idx_lemline_creation_event_outbox_cleanup
    ON lemline_creation_event_outbox (cleanup_after);

CREATE INDEX idx_lemline_creation_event_outbox_workflow_id
    ON lemline_creation_event_outbox (workflow_id);
