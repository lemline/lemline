CREATE TABLE workflow_definitions (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    definition TEXT NOT NULL,
    UNIQUE (name, version)
);

-- Create index for efficient querying on name and version
CREATE INDEX idx_workflow_def_name_version ON workflow_definitions(name, version); 