-- Use the table name from com.lemline.runner.repositories.FORKS_TABLE
CREATE TABLE IF NOT EXISTS lemline_forks
(
    id                 UUID PRIMARY KEY,
    workflow_id        UUID,
    workflow_namespace VARCHAR(255),
    workflow_name      VARCHAR(255),
    workflow_version   VARCHAR(255),
    fork_id            UUID           NOT NULL,
    fork_position      TEXT           NOT NULL,
    fork_name          VARCHAR(255)   NOT NULL,
    fork_output        TEXT,
    run_status         VARCHAR(50)    NOT NULL,
    run_at             TIMESTAMPTZ(6),
    created_at         TIMESTAMPTZ(6) NOT NULL,
    updated_at         TIMESTAMPTZ(6)
);

-- Create an index for efficient querying on workflow_id
CREATE INDEX IF NOT EXISTS idx_lemline_forks_workflow_id
    ON lemline_forks (workflow_id);

-- Create an index for efficient querying on fork_id
CREATE INDEX IF NOT EXISTS idx_lemline_forks_fork_id
    ON lemline_forks (fork_id);
