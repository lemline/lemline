-- Use the table name from WorkflowRepository (tableName = "workflows")
CREATE TABLE IF NOT EXISTS workflows
(
    id         VARCHAR(36),
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition CLOB         NOT NULL,
    CONSTRAINT pk_workflows_name_version PRIMARY KEY (name, version),
    CONSTRAINT uk_workflows_id UNIQUE (id)
);

-- Create an index for efficient querying on name
CREATE INDEX IF NOT EXISTS idx_workflows_name ON workflows (name);
