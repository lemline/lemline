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
    fork_position TEXT NOT NULL,
    compete BOOLEAN NOT NULL,
    branch_count INT NOT NULL,

    -- Cleanup tracking
    outbox_completed_at TIMESTAMPTZ(6),

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,

    -- Constraints
    CONSTRAINT uk_forks_workflow_position UNIQUE (workflow_id, fork_position)
);

-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches (
    -- Foreign key to parent fork
    fork_id UUID NOT NULL,
    branch_index INT NOT NULL,

    -- Branch metadata
    branch_name TEXT NOT NULL,
    branch_node_position TEXT NOT NULL,

    -- Execution state
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    output TEXT,
    error TEXT,

    -- Timestamps
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    PRIMARY KEY (fork_id, branch_index),

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (fork_id)
        REFERENCES lemline_forks(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_branch_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAULTED')),

    CONSTRAINT chk_branch_index
        CHECK (branch_index >= 0)
);

-- Indexes for performance
CREATE INDEX idx_fork_branches_status
    ON lemline_fork_branches(fork_id, status);

-- Partial index for fast completed branch lookup (PostgreSQL-specific)
CREATE INDEX idx_fork_branches_completed
    ON lemline_fork_branches(fork_id)
    WHERE status = 'COMPLETED';

CREATE INDEX idx_forks_created
    ON lemline_forks(created_at);

-- Create index for efficient cleanup queries
CREATE INDEX idx_lemline_forks_completed
    ON lemline_forks (outbox_completed_at)
    WHERE outbox_completed_at IS NOT NULL;

-- Note: (workflow_id, fork_position) has unique constraint, no separate index needed

-- Comments for documentation
COMMENT ON TABLE lemline_forks IS 'Fork metadata for async parallel execution';
COMMENT ON TABLE lemline_fork_branches IS 'Individual branch execution state';
COMMENT ON COLUMN lemline_forks.compete IS 'True if compete mode (first wins), false if cooperative (wait all)';
COMMENT ON COLUMN lemline_fork_branches.status IS 'Branch execution status: PENDING, RUNNING, COMPLETED, FAILED';
