-- Use the table name from WorkflowRepository (tableName = "workflows")
CREATE TABLE IF NOT EXISTS workflows
(
    id             VARCHAR(36) PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    version        VARCHAR(255) NOT NULL,
    definition     MEDIUMTEXT NOT NULL,  -- MySQL MEDIUMTEXT supports up to 16MB
    CONSTRAINT uk_workflows_name_version UNIQUE (name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create index for efficient querying on name and version
CREATE INDEX idx_workflows_name_version ON workflows (name, version); 