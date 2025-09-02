-- Use the table name from com.lemline.runner.models.RetryModel
CREATE TABLE IF NOT EXISTS lemline_retries
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
    message              CLOB,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_retries_workflow_id
    ON lemline_retries (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_retries_parent_id
    ON lemline_retries (parent_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_retries_status_delayed_until
    ON lemline_retries (outbox_status, outbox_delayed_until);
