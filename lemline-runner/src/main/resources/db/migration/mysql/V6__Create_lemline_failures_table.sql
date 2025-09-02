-- Use the table name from com.lemline.runner.models.FailureModel
CREATE TABLE IF NOT EXISTS lemline_failures
(
    id                BINARY(16) PRIMARY KEY,
    workflow_id       BINARY(16)   NOT NULL,
    workflow_name     VARCHAR(255) NOT NULL,
    workflow_version  VARCHAR(255) NOT NULL,
    workflow_position TEXT         NOT NULL,
    workflow_state    MEDIUMTEXT   NOT NULL,
    parent_id         BINARY(16),
    message           MEDIUMTEXT,
    reason            VARCHAR(255) NOT NULL,
    error_class       TEXT         NOT NULL,
    error_message     TEXT,
    error_stacktrace  MEDIUMTEXT   NOT NULL,
    created_at        TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_failures_workflow_id
    ON lemline_failures (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_failures_parent_id
    ON lemline_failures (parent_id);
