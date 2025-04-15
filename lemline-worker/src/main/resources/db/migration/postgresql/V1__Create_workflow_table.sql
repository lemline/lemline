CREATE TABLE workflows
(
    id             UUID PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    version        VARCHAR(255) NOT NULL,
    definition     TEXT         NOT NULL,
    version_number BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_workflows_name_version UNIQUE (name, version)
);

-- Create index for efficient querying on name and version
CREATE INDEX idx_workflows_name_version ON workflows (name, version);