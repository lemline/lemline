-- Fork metadata table
-- Stores fork configuration and parent workflow state
CREATE TABLE lemline_forks (
    -- Primary key
    id UUID NOT NULL PRIMARY KEY,

    -- Workflow instance information
    workflow_id UUID,
    workflow_namespace VARCHAR(255),
    workflow_name VARCHAR(255),
    workflow_version VARCHAR(255),
    workflow_position TEXT,
    workflow_state TEXT,

    -- Fork-specific fields
    position TEXT NOT NULL,
    compete BOOLEAN NOT NULL,
    output TEXT,

    -- Completion and cleanup tracking
    completed_at TIMESTAMPTZ(6),
    cleanup_after TIMESTAMPTZ(6),
    failed_at TIMESTAMPTZ,

    -- Error details (inline instead of FK to failures table)
    error_reason VARCHAR(255),
    error_class VARCHAR(500),
    error_message TEXT,
    error_stacktrace TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Create index for efficient cleanup queries
CREATE INDEX idx_lemline_forks_cleanup
    ON lemline_forks (cleanup_after)
    WHERE cleanup_after IS NOT NULL;

-- Comments for documentation
COMMENT ON TABLE lemline_forks IS 'Fork metadata for async parallel execution';
COMMENT ON COLUMN lemline_forks.compete IS 'True if compete mode (first wins), false if cooperative (wait all)';
