-- Use the table name from com.lemline.runner.models.WaitModel
CREATE TABLE IF NOT EXISTS lemline_waits
(
    id                   uuid PRIMARY KEY,
    workflow_id          uuid           NOT NULL,
    workflow_name        VARCHAR(255)   NOT NULL,
    workflow_version     VARCHAR(255)   NOT NULL,
    workflow_position    TEXT           NOT NULL,
    workflow_state       TEXT           NOT NULL,
    parent_id            uuid,
    outbox_status        VARCHAR(50)    NOT NULL,
    outbox_scheduled_for TIMESTAMPTZ(6) NOT NULL,
    outbox_delayed_until TIMESTAMPTZ(6) NOT NULL,
    outbox_attempt_count INTEGER        NOT NULL DEFAULT 0,
    outbox_last_error    TEXT,
    created_at           TIMESTAMPTZ(6) NOT NULL,
    updated_at           TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_waits_workflow_id
    ON lemline_waits (workflow_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX IF NOT EXISTS idx_lemline_waits_status_delayed_until
    ON lemline_waits (outbox_status, outbox_delayed_until);
