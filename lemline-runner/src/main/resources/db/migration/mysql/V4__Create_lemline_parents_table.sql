-- Use the table name from com.lemline.runner.repositories.RETRY_TABLE
CREATE TABLE IF NOT EXISTS lemline_parents
(
    id                 BINARY(16) PRIMARY KEY,
    workflow_id        BINARY(16)   NOT NULL,
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name      VARCHAR(255) NOT NULL,
    workflow_version   VARCHAR(255) NOT NULL,
    workflow_position  TEXT         NOT NULL,
    workflow_state     MEDIUMTEXT   NOT NULL,
    parent_id          BINARY(16),
    run_status         VARCHAR(50)  NOT NULL,
    run_at             TIMESTAMP(6),
    created_at         TIMESTAMP(6) NOT NULL,
    updated_at         TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_parents_workflow_id
    ON lemline_parents (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_parents_parent_id
    ON lemline_parents (parent_id);

-- Create an index for efficient querying on status and run_at
CREATE INDEX idx_lemline_parents_status_run_at
    ON lemline_parents (run_status, run_at);
