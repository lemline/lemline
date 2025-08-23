-- Use the table name from com.lemline.runner.models.RetryModel
CREATE TABLE IF NOT EXISTS lemline_retries
(
    id                   VARCHAR(36) PRIMARY KEY,
    workflow_id          VARCHAR(36)              NOT NULL,
    workflow_name        VARCHAR(255)             NOT NULL,
    workflow_version     VARCHAR(255)             NOT NULL,
    workflow_position    CLOB                     NOT NULL,
    workflow_state       CLOB                     NOT NULL,
    schedule_id          VARCHAR(36),
    parent_id            VARCHAR(36),
    outbox_status        VARCHAR(50)              NOT NULL,
    outbox_scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_delayed_until TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_attempt_count INTEGER                  NOT NULL DEFAULT 0,
    outbox_last_error    CLOB,
    message              CLOB
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_retries_workflow_id
    ON lemline_retries (workflow_id);

-- Create an index for efficient querying on status and delayed_until
CREATE INDEX idx_lemline_retries_status_delayed_until
    ON lemline_retries (outbox_status, outbox_delayed_until);
