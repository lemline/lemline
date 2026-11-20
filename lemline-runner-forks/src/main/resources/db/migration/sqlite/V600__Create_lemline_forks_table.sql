-- Fork metadata table
-- Stores fork configuration and parent workflow state
CREATE TABLE lemline_forks
(
    -- Primary key
    id                 TEXT          NOT NULL PRIMARY KEY,
    -- Workflow instance information
    workflow_id        TEXT,
    workflow_namespace TEXT,
    workflow_name      TEXT,
    workflow_version   TEXT,
    workflow_position  TEXT,
    workflow_state     TEXT,
    -- Fork-specific fields
    position           TEXT NOT NULL,
    compete            BOOLEAN       NOT NULL,
    output             TEXT,
    -- Completion and cleanup tracking
    completed_at       INTEGER,
    cleanup_after      INTEGER,
    failed_at          INTEGER,
    -- Error details (inline instead of FK to failures table)
    error_reason       TEXT,
    error_class        TEXT,
    error_message      TEXT,
    error_stacktrace   TEXT,
    -- Timestamps
    created_at         INTEGER     NOT NULL DEFAULT (CAST(round(unixepoch('subsec') * 1000000) AS INTEGER)),
    updated_at         INTEGER
);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_forks_cleanup
    ON lemline_forks (cleanup_after);
