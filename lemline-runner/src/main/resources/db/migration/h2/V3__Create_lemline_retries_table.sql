-- Use the table name from com.lemline.runner.repositories.RETRY_TABLE
CREATE TABLE IF NOT EXISTS lemline_retries
(
    id                      UUID PRIMARY KEY,
    workflow_id             UUID                     NOT NULL,
    workflow_namespace      VARCHAR(255)             NOT NULL,
    workflow_name           VARCHAR(255)             NOT NULL,
    workflow_version        VARCHAR(255)             NOT NULL,
    workflow_position       CLOB                     NOT NULL,
    workflow_state          CLOB                     NOT NULL,
    parent_id               UUID,
    error_reason            VARCHAR(255)             NOT NULL,
    error_class             CLOB                     NOT NULL,
    error_message           CLOB,
    error_stacktrace        CLOB                     NOT NULL,
    outbox_status           VARCHAR(50)              NOT NULL,
    outbox_scheduled_for    TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_delayed_until    TIMESTAMP WITH TIME ZONE NOT NULL,
    outbox_attempt_count    INTEGER                  NOT NULL DEFAULT 0,
    outbox_error_class      CLOB,
    outbox_error_message    CLOB,
    outbox_error_stacktrace CLOB,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE
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
