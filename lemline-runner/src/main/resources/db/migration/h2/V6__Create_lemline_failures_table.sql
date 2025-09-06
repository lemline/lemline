-- Use the table name from com.lemline.runner.repositories.FAILURES_TABLE
CREATE TABLE IF NOT EXISTS lemline_failures
(
    id                UUID PRIMARY KEY,
    workflow_id       UUID,
    workflow_name     VARCHAR(255),
    workflow_version  VARCHAR(255),
    workflow_position CLOB,
    workflow_state    CLOB,
    parent_id         UUID,
    payload           CLOB,
    error_reason      VARCHAR(255)             NOT NULL,
    error_class       CLOB                     NOT NULL,
    error_message     CLOB,
    error_stacktrace  CLOB                     NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_failures_workflow_id
    ON lemline_failures (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_failures_parent_id
    ON lemline_failures (parent_id);
