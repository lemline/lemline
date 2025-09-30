-- Use the table name from com.lemline.runner.repositories.FORKS_TABLE
CREATE TABLE IF NOT EXISTS lemline_forks
(
    id                 UUID PRIMARY KEY,
    workflow_id        UUID,
    workflow_namespace VARCHAR(255)             NOT NULL,
    workflow_name      VARCHAR(255)             NOT NULL,
    workflow_version   VARCHAR(255)             NOT NULL,
    fork_id            UUID                     NOT NULL,
    fork_position      CLOB                     NOT NULL,
    fork_name          VARCHAR(255)             NOT NULL,
    fork_output        CLOB,
    run_status         VARCHAR(255)             NOT NULL,
    run_at             TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX idx_lemline_forks_workflow_id
    ON lemline_forks (workflow_id);

-- Create an index for efficient querying on fork_id
CREATE INDEX idx_lemline_forks_fork_id
    ON lemline_forks (fork_id);
