-- Use the table name from WorkflowRepository (tableName = "workflows")
CREATE TABLE workflows
(
    id             VARCHAR(36) PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    version        VARCHAR(255) NOT NULL,
    definition     TEXT NOT NULL,  -- PostgreSQL TEXT has unlimited size
    CONSTRAINT uk_workflows_name_version UNIQUE (name, version)
) WITH (
    ENCODING = 'UTF8'  -- PostgreSQL UTF8 supports full Unicode (up to 4 bytes)
);

-- Create index for efficient querying on name and version
CREATE INDEX idx_workflows_name_version ON workflows (name, version);