-- Fork metadata table
-- Stores fork configuration and parent workflow state
CREATE TABLE lemline_forks
(
    -- Primary key
    id                 UUID          NOT NULL PRIMARY KEY,
    -- Workflow instance information
    workflow_id        UUID,
    workflow_namespace VARCHAR(255),
    workflow_name      VARCHAR(255),
    workflow_version   VARCHAR(255),
    workflow_position  VARCHAR(1000),
    workflow_state     CLOB,
    -- Fork-specific fields
    position           VARCHAR(1000) NOT NULL,
    compete            BOOLEAN       NOT NULL,
    output             CLOB,
    -- Completion and cleanup tracking
    completed_at       TIMESTAMP WITH TIME ZONE,
    cleanup_after      TIMESTAMP WITH TIME ZONE,
    failed_at          TIMESTAMP,
    -- Error details (inline instead of FK to failures table)
    error_reason       VARCHAR(255),
    error_class        VARCHAR(500),
    error_message      CLOB,
    error_stacktrace   CLOB,
    -- Timestamps
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP
);

-- Create index for cleanup queries
CREATE INDEX idx_lemline_forks_cleanup
    ON lemline_forks (cleanup_after);
