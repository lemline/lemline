-- Use the table name from com.lemline.runner.repositories.FAILURES_TABLE
CREATE TABLE IF NOT EXISTS lemline_failures
(
    id                 BINARY(16) PRIMARY KEY,
    workflow_id        BINARY(16),
    workflow_namespace VARCHAR(255),
    workflow_name      VARCHAR(255),
    workflow_version   VARCHAR(255),
    workflow_position  TEXT,
    workflow_state     MEDIUMTEXT,
    payload            MEDIUMTEXT,
    error_reason       VARCHAR(255) NOT NULL,
    error_class        TEXT         NOT NULL,
    error_message      TEXT,
    error_stacktrace   MEDIUMTEXT   NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL,
    updated_at         TIMESTAMP(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_failures_workflow_id
    ON lemline_failures (workflow_id);

