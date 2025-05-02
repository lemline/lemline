-- Use the table name from WorkflowRepository (tableName = "workflows")
CREATE TABLE IF NOT EXISTS workflows
(
    id         VARCHAR(36),
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition MEDIUMTEXT   NOT NULL,
    PRIMARY KEY (name, version),
    UNIQUE KEY uk_workflows_id (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Create an index for efficient querying on name
CREATE INDEX idx_workflows_name ON workflows (name);
