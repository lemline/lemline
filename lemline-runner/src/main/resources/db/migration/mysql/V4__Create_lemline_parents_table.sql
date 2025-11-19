-- Use the table name from com.lemline.runner.repositories.PARENT_TABLE
CREATE TABLE IF NOT EXISTS lemline_parents
(
    id                 BINARY(16) PRIMARY KEY,
    workflow_id        BINARY(16)   NOT NULL,
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name      VARCHAR(255) NOT NULL,
    workflow_version   VARCHAR(255) NOT NULL,
    workflow_position  TEXT         NOT NULL,
    workflow_state     MEDIUMTEXT   NOT NULL,
    child_id           BINARY(16)   NOT NULL,
    parent_id          BINARY(16),
    created_at         TIMESTAMP(6) NOT NULL,
    updated_at         TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_parents_workflow_id
    ON lemline_parents (workflow_id);

-- Create a unique index on child_id (each child can have only one parent)
CREATE UNIQUE INDEX idx_lemline_parents_child_id
    ON lemline_parents (child_id);

-- Create an index for efficient querying on parent_id (convenience for users)
CREATE INDEX idx_lemline_parents_parent_id
    ON lemline_parents (parent_id);
