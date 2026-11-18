-- Lifecycle events that must survive a crash between the accepting commit and their publication:
-- workflow.created and the initial /do task.created (written with the initial command) and fork
-- branch task.created (written with the fork). The primary key is the deterministic CloudEvent ID.
CREATE TABLE lemline_creation_event_outbox
(
    id                      uuid PRIMARY KEY,
    workflow_id             uuid           NOT NULL,
    workflow_namespace      VARCHAR(255)   NOT NULL,
    event_type              VARCHAR(255)   NOT NULL,
    event_time              TIMESTAMPTZ(6) NOT NULL,
    event_payload           TEXT           NOT NULL,
    outbox_scheduled_for    TIMESTAMPTZ(6),
    outbox_delayed_until    TIMESTAMPTZ(6),
    outbox_attempt_count    INTEGER        NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),
    outbox_failed_at        TIMESTAMPTZ(6),
    cleanup_after           TIMESTAMPTZ(6),
    created_at              TIMESTAMPTZ(6) NOT NULL,
    updated_at              TIMESTAMPTZ(6)
);

CREATE INDEX IF NOT EXISTS idx_lemline_creation_event_outbox_processing
    ON lemline_creation_event_outbox (outbox_completed_at, outbox_failed_at, outbox_delayed_until, outbox_attempt_count)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_lemline_creation_event_outbox_cleanup
    ON lemline_creation_event_outbox (cleanup_after)
    WHERE cleanup_after IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_lemline_creation_event_outbox_workflow_id
    ON lemline_creation_event_outbox (workflow_id);
