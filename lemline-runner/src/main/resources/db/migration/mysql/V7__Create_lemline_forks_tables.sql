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

    -- Cleanup tracking
    outbox_completed_at TIMESTAMP(6),
    failed_at TIMESTAMP NULL DEFAULT NULL,
    failure_id BINARY(16),

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL,

    -- Constraints
    CONSTRAINT uk_forks_workflow_position UNIQUE (workflow_id, `position`(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Branch execution table
-- One row per branch, tracks individual branch execution
CREATE TABLE lemline_fork_branches (
    -- Foreign key to parent fork
    fork_id BINARY(16) NOT NULL,

    -- Branch metadata
    name VARCHAR(255) NOT NULL,

    -- Execution state
    output MEDIUMTEXT,
    failure_id BINARY(16),

    -- Timestamps
    completed_at TIMESTAMP NULL DEFAULT NULL,
    failed_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    PRIMARY KEY (fork_id, name),

    CONSTRAINT fk_fork_branches_fork
        FOREIGN KEY (fork_id)
        REFERENCES lemline_forks(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Create index for cleanup queries
CREATE INDEX idx_lemline_forks_completed
    ON lemline_forks (outbox_completed_at);

-- Note: (workflow_id, position) has unique constraint, no separate index needed

-- Foreign key constraints to failures table
ALTER TABLE lemline_forks
    ADD CONSTRAINT fk_forks_failure
        FOREIGN KEY (failure_id)
        REFERENCES lemline_failures(id)
        ON DELETE SET NULL;

ALTER TABLE lemline_fork_branches
    ADD CONSTRAINT fk_fork_branches_failure
        FOREIGN KEY (failure_id)
        REFERENCES lemline_failures(id)
        ON DELETE SET NULL;
