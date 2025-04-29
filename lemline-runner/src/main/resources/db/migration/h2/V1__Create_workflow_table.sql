-- Use the table name from WorkflowRepository (tableName = "workflows")
CREATE TABLE IF NOT EXISTS workflows
(
    id         VARCHAR(36) PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition CLOB         NOT NULL,
    CONSTRAINT uk_workflows_name_version UNIQUE (name, version)
);

-- Create an index for efficient querying on name and version
CREATE INDEX IF NOT EXISTS idx_workflows_name_version ON workflows (name, version);
