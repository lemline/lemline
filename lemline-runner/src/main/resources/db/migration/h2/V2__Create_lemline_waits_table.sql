-- Use the table name from com.lemline.runner.models.WaitModel
CREATE TABLE IF NOT EXISTS lemline_waits
(
    id                   UUID PRIMARY KEY,
    workflow_id          UUID                     NOT NULL,
    workflow_name        VARCHAR(255)             NOT NULL,
    workflow_version     VARCHAR(255)             NOT NULL,
    workflow_position    CLOB                     NOT NULL,
    workflow_state       CLOB                     NOT NULL,
    parent_id            UUID,
    outbox_status        VARCHAR(50)              NOT NULL,
    outbox_scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_delayed_until TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_attempt_count INTEGER                  NOT NULL DEFAULT 0,
    outbox_last_error    CLOB,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE,
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_waits_workflow_id
    ON lemline_waits (workflow_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_waits_status_delayed_until
    ON lemline_waits (outbox_status, outbox_delayed_until);
