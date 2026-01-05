-- Fork metadata table
-- Stores fork configuration and parent workflow state
CREATE TABLE lemline_forks (
    -- Primary key
    id BINARY(16) NOT NULL PRIMARY KEY,

    -- Workflow instance information
    workflow_id BINARY(16),
    workflow_namespace VARCHAR(255),
    workflow_name VARCHAR(255),
    workflow_version VARCHAR(255),
    workflow_position VARCHAR(1000),
    workflow_state MEDIUMTEXT,

    -- Fork-specific fields
    `position` VARCHAR(1000) NOT NULL,
    compete TINYINT(1) NOT NULL,
    output MEDIUMTEXT,

    -- Completion and cleanup tracking
    completed_at TIMESTAMP(6) NULL DEFAULT NULL,
    cleanup_after TIMESTAMP(6),
    failed_at TIMESTAMP NULL DEFAULT NULL,

    -- Error details (inline instead of FK to failures table)
    error_reason VARCHAR(255),
    error_class VARCHAR(500),
    error_message TEXT,
    error_stacktrace MEDIUMTEXT,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL,

    -- Constraints
    CONSTRAINT uk_forks_workflow_position UNIQUE (workflow_id, `position`(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Create index for cleanup queries
CREATE INDEX idx_lemline_forks_cleanup
    ON lemline_forks (cleanup_after);

-- Note: (workflow_id, position) has unique constraint, no separate index needed
