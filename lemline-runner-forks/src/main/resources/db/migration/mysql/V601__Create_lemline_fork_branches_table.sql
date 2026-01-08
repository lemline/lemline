-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches (
    -- Primary key
    id BINARY(16) NOT NULL PRIMARY KEY,

    -- Foreign key to parent fork
    fork_id BINARY(16) NOT NULL,

    -- Branch metadata
    branch_position VARCHAR(255) NOT NULL,

    -- Execution state
    branch_output MEDIUMTEXT,

    -- Timestamps
    completed_at TIMESTAMP NULL DEFAULT NULL,
    failed_at TIMESTAMP NULL DEFAULT NULL,

    -- Error details (inline instead of FK to failures table)
    error_reason VARCHAR(255),
    error_class VARCHAR(500),
    error_message TEXT,
    error_stacktrace MEDIUMTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (fork_id)
        REFERENCES lemline_forks(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Index for efficient branch lookup by fork_id (used by deleteByForkId)
CREATE INDEX idx_lemline_fork_branches_fork_id ON lemline_fork_branches (fork_id);

-- Composite index for efficient branch retrieval with ORDER BY branch_position (used by findByForkId)
CREATE INDEX idx_lemline_fork_branches_fork_position ON lemline_fork_branches (fork_id, branch_position);
