-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches (
    -- Primary key
    id UUID NOT NULL PRIMARY KEY,

    -- Foreign key to parent fork
    fork_id UUID NOT NULL,

    -- Branch metadata
    branch_position VARCHAR(255) NOT NULL,

    -- Execution state
    branch_output TEXT,

    -- Timestamps
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,

    -- Error details (inline instead of FK to failures table)
    error_reason VARCHAR(255),
    error_class VARCHAR(500),
    error_message TEXT,
    error_stacktrace TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (fork_id)
        REFERENCES lemline_forks(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_lemline_fork_branches_fork_id ON lemline_fork_branches (fork_id);

-- Comments for documentation
COMMENT ON TABLE lemline_fork_branches IS 'Individual branch execution state';
