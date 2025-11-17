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
    parent_id BINARY(16),

    -- Fork-specific fields
    fork_position VARCHAR(1000) NOT NULL,
    compete TINYINT(1) NOT NULL,
    branch_count INT NOT NULL,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL,

    -- Unique constraint: one active fork per workflow position
    CONSTRAINT uk_forks_workflow_position UNIQUE (workflow_id, fork_position(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches (
    -- Foreign key to parent fork
    fork_id BINARY(16) NOT NULL,
    branch_index INT NOT NULL,

    -- Branch metadata
    branch_name VARCHAR(255) NOT NULL,
    branch_node_position VARCHAR(1000) NOT NULL,

    -- Execution state
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    output MEDIUMTEXT,
    error TEXT,

    -- Timestamps
    completed_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Indexes for performance
CREATE INDEX idx_fork_branches_status
    ON lemline_fork_branches(fork_id, status);

CREATE INDEX idx_forks_created
    ON lemline_forks(created_at);

-- Note: (workflow_id, fork_position) has unique constraint, no separate index needed
