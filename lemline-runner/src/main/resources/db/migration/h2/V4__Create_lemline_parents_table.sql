-- Use the table name from com.lemline.runner.repositories.PARENTS_TABLE
CREATE TABLE IF NOT EXISTS lemline_parents
(
    id                 UUID PRIMARY KEY,
    workflow_id        UUID                     NOT NULL,
    workflow_namespace VARCHAR(255)             NOT NULL,
    workflow_name      VARCHAR(255)             NOT NULL,
    workflow_version   VARCHAR(255)             NOT NULL,
    workflow_position  CLOB                     NOT NULL,
    workflow_state     CLOB                     NOT NULL,
    parent_id          UUID,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_parents_workflow_id
    ON lemline_parents (workflow_id);

-- Create an index for efficient querying on parent_id
CREATE INDEX idx_lemline_parents_parent_id
    ON lemline_parents (parent_id);
