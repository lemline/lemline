-- Lifecycle events that must survive a crash between the accepting commit and their publication:
-- workflow.created and the initial /do task.created (written with the initial command) and fork
-- branch task.created (written with the fork). The primary key is the deterministic CloudEvent ID.
CREATE TABLE IF NOT EXISTS lemline_creation_event_outbox
(
    id                      UUID PRIMARY KEY,
    workflow_id             UUID                     NOT NULL,
    workflow_namespace      VARCHAR(255)             NOT NULL,
    event_type              VARCHAR(255)             NOT NULL,
    event_time              TIMESTAMP WITH TIME ZONE NOT NULL,
    event_payload           CLOB                     NOT NULL,
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

CREATE INDEX idx_lemline_creation_event_outbox_processing
    ON lemline_creation_event_outbox (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count);

CREATE INDEX idx_lemline_creation_event_outbox_cleanup
    ON lemline_creation_event_outbox (cleanup_after);

CREATE INDEX idx_lemline_creation_event_outbox_workflow_id
    ON lemline_creation_event_outbox (workflow_id);
